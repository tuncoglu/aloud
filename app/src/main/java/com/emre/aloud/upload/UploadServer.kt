package com.emre.aloud.upload

import android.content.Context
import android.os.storage.StorageManager
import com.emre.aloud.books.Book
import com.emre.aloud.books.BooksRepository
import com.emre.aloud.data.PlayerPrefs
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The parts of the uploader that touch Android, behind an interface so the
 * routing (and its tests) can run on the JVM without a Context.
 */
// The route module is top-level, so its constants live at file scope.
private const val MAX_CHUNK_BYTES = 4L * 1024 * 1024
private const val FREE_SPACE_MARGIN = 32L * 1024 * 1024
private const val IDLE_TIMEOUT_MS = 2 * 60_000L
private const val MAX_BAD_PINS_PER_ADDRESS = 10
private const val PIN_LOCKOUT_MS = 5 * 60_000L
private const val MAX_TRACKED_ADDRESSES = 256
private const val NAME_LOCK_WAIT_MS = 2_000L
private const val MAX_NAME_BYTES = 200

interface UploadStore {
    val dir: File
    fun list(): List<Book>
    suspend fun deletePosition(name: String)
    fun freeBytes(): Long
}

/** Production store: the watch's private books directory, DataStore positions.
 *  The parameter is named appContext — "context" collides with a Ktor function
 *  of that name in modules that also import the Ktor application API. */
class AndroidUploadStore(private val appContext: Context) : UploadStore {
    override val dir: File get() = BooksRepository.booksDir(appContext)
    override fun list(): List<Book> = BooksRepository.list(appContext)
    override suspend fun deletePosition(name: String) = PlayerPrefs.deletePos(appContext, name)

    /** Space actually obtainable: getAllocatableBytes counts the cached data the
     *  system would evict for us, so a book that would really fit is not refused.
     *  usableSpace is the fallback. */
    override fun freeBytes(): Long = try {
        val sm = appContext.getSystemService(StorageManager::class.java)
        sm.getAllocatableBytes(sm.getUuidForPath(dir))
    } catch (_: Exception) {
        dir.usableSpace
    }
}

/** Shared mutable state of one upload server run (moved out of the routing so
 *  tests can drive the routes directly). */
internal class UploadState(
    @PublishedApi internal val lockWaitMs: Long = NAME_LOCK_WAIT_MS,
    clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var lastActivityMs = System.currentTimeMillis()
    val pinGuard = PinGuard(clock)
    private val nameLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Runs [block] while holding the lock for [name], or returns null if
     * another request has held it for [lockWaitMs]. Two POSTs to one `.part`
     * interleave their writes and can corrupt the book; a delete racing a
     * write is no better. The loser is told to retry (409) rather than queued
     * behind a stalled transfer.
     */
    internal suspend inline fun <T> withNameLock(name: String, block: () -> T): T? {
        val mutex = lockFor(name)
        val deadline = System.currentTimeMillis() + lockWaitMs
        while (!mutex.tryLock()) {
            if (System.currentTimeMillis() >= deadline) return null
            delay(25)
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    @PublishedApi
    internal fun lockFor(name: String): Mutex = nameLocks.getOrPut(name) { Mutex() }
}

/**
 * Counts wrong-PIN guesses per source address. An address that reaches
 * [MAX_BAD_PINS_PER_ADDRESS] is refused outright - even with the right PIN, or
 * the lockout would only slow a guesser down - until [PIN_LOCKOUT_MS] passes.
 * Other addresses are untouched, so one noisy device on the LAN can no longer
 * switch the uploader off for the person holding the watch.
 *
 * The server's idle timer already ends a run two minutes after the last
 * *successful* request, so a guesser's total budget is bounded by that too.
 */
internal class PinGuard(private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry(var failures: Int = 0, var lockedUntilMs: Long = 0, var lastFailureMs: Long = 0)

    private val entries = HashMap<String, Entry>()

    /** Milliseconds until [address] may try again, or 0 if it may try now. */
    @Synchronized
    fun lockedForMs(address: String): Long {
        val until = entries[address]?.lockedUntilMs ?: return 0
        return (until - clock()).coerceAtLeast(0)
    }

    /** Records a wrong guess; returns true if it just locked [address] out. */
    @Synchronized
    fun recordFailure(address: String): Boolean {
        val now = clock()
        if (entries.size >= MAX_TRACKED_ADDRESSES && address !in entries) evict(now)
        val e = entries.getOrPut(address) { Entry() }
        // Stale failures from long ago should not add up to a lockout.
        if (now - e.lastFailureMs > PIN_LOCKOUT_MS) e.failures = 0
        e.lastFailureMs = now
        e.failures++
        if (e.failures < MAX_BAD_PINS_PER_ADDRESS) return false
        e.failures = 0
        e.lockedUntilMs = now + PIN_LOCKOUT_MS
        return true
    }

    private fun evict(now: Long) {
        entries.values.removeAll { it.lockedUntilMs <= now && now - it.lastFailureMs > PIN_LOCKOUT_MS }
        if (entries.size >= MAX_TRACKED_ADDRESSES) {
            entries.keys.firstOrNull()?.let { entries.remove(it) }
        }
    }
}

/**
 * A request must name the watch by IP address (or localhost). A page on a
 * hostile domain that re-points its DNS at the watch (DNS rebinding) still
 * sends its own hostname in `Host`, so it is turned away before it can read
 * or write anything - the PIN alone would not stop a page the user was
 * tricked into typing it into.
 */
internal fun hostAllowed(hostHeader: String?): Boolean {
    val raw = hostHeader?.trim().orEmpty()
    val host = when {
        raw.startsWith("[") -> return IPV6_HOST.matches(raw)
        raw.count { it == ':' } > 1 -> return false // unbracketed IPv6 is not a valid Host
        else -> raw.substringBefore(':')
    }
    return host.equals("localhost", ignoreCase = true) || IPV4_HOST.matches(host)
}

private val IPV4_HOST = Regex("""(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}""")
private val IPV6_HOST = Regex("""\[[0-9a-fA-F:.%a-zA-Z0-9]*:[0-9a-fA-F:.%a-zA-Z0-9]*](:\d{1,5})?""")

/**
 * Tiny embedded HTTP uploader: PC browser -> http://<watch-ip>:8080.
 *
 * Every mutating endpoint requires the 6-digit [pin] shown on the watch, so a
 * stranger on the same WiFi — or a web page you happen to open, which could
 * otherwise POST here cross-origin without a preflight — cannot write to or
 * wipe the library. The PIN is never embedded in the served page.
 *
 * Endpoint contract (no multipart on purpose):
 *   GET    /                               -> uploader HTML page (no PIN needed)
 *   POST   /book?name=&offset=&total=&pin= -> raw bytes written at offset into
 *                                             books/<name>.part, renamed to
 *                                             <name> once offset+len == total
 *   GET    /books?pin=                     -> JSON list
 *   DELETE /book?name=&pin=                -> delete file + its saved positions
 *   DELETE /part?name=&pin=                -> drop a partial upload only
 */
class UploadServer(
    private val store: UploadStore,
    private val pin: String,
    private val onAutoStop: () -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val state = UploadState()
    private var server: EmbeddedServer<*, *>? = null
    private var autoStopJob: Job? = null

    val isRunning: Boolean get() = server != null

    fun start(port: Int) {
        if (server != null) return
        reapPartials(store.dir)
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            uploadRoutes(store, state, pin)
        }.start(wait = false)
        startAutoStop()
    }

    fun stop() {
        autoStopJob?.cancel()
        autoStopJob = null
        server?.stop(0, 0)
        server = null
    }

    private fun startAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (System.currentTimeMillis() - state.lastActivityMs > IDLE_TIMEOUT_MS) {
                    // Stop the *service* too: stopping only the engine left the
                    // notification up and the UI claiming "Uploader on".
                    server?.stop(0, 0)
                    server = null
                    autoStopJob = null
                    onAutoStop()
                    return@launch
                }
            }
        }
    }

    companion object {
        const val PIN_LENGTH = 6

        /** Fresh PIN per server start; shown on the watch, never served over HTTP. */
        fun newPin(): String =
            SecureRandom().nextInt(1_000_000).toString().padStart(PIN_LENGTH, '0')
    }
}

/**
 * Drops abandoned partial uploads. Only one server runs at a time, so any
 * `.part` left on disk at start-up is debris from a transfer that died — a
 * browser tab closed mid-upload never reaches the client's own cleanup call,
 * and nothing else ever reaps them.
 */
internal fun reapPartials(dir: File) {
    dir.listFiles { f -> f.isFile && f.name.endsWith(BooksRepository.PART_SUFFIX) }
        ?.forEach { it.delete() }
}

/** The routes, as a standalone module so the tests can mount them with a fake
 *  [UploadStore] through testApplication. */
internal fun Application.uploadRoutes(
    store: UploadStore,
    state: UploadState,
    pin: String,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (!hostAllowed(call.request.headers[HttpHeaders.Host] ?: call.request.origin.serverHost)) {
            call.respondText(
                "{\"error\":\"use the watch's IP address\"}",
                ContentType.Application.Json,
                HttpStatusCode(421, "Misdirected Request"),
            )
            finish()
        }
    }
    routing {
        get("/") {
            call.respondText(UPLOADER_HTML, ContentType.Text.Html)
        }

        post("/book") {
            if (!authorize(state, pin)) return@post
            val name = sanitizeName(call.request.queryParameters["name"])
                ?: return@post fail("bad name", HttpStatusCode.BadRequest)
            val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
            val total = call.request.queryParameters["total"]?.toLongOrNull() ?: -1L

            // Every upload must declare its size. Without this an omitted
            // `total` skipped the free-space check below and let a caller
            // append 4 MiB chunks to a .part forever, filling the watch.
            if (total <= 0) {
                return@post fail("missing total", HttpStatusCode.BadRequest)
            }

            // A chunk is bounded: the body streams to disk instead of being
            // buffered whole, and an oversized one is refused before it can
            // exhaust a watch's RAM.
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > MAX_CHUNK_BYTES) {
                return@post fail("chunk too large", HttpStatusCode.PayloadTooLarge)
            }

            val ran = state.withNameLock(name) {
                val part = File(store.dir, "$name${BooksRepository.PART_SUFFIX}")
                // Writes may only land inside what has already been received (an
                // append, or a re-send of an earlier chunk) — never at an
                // arbitrary caller-chosen file position.
                if (offset < 0 || offset > part.length()) {
                    return@post fail("bad offset", HttpStatusCode.BadRequest)
                }
                if (offset >= total) {
                    return@post fail("offset past total", HttpStatusCode.BadRequest)
                }
                if (offset == 0L && store.freeBytes() < total + FREE_SPACE_MARGIN) {
                    return@post fail("not enough free space", HttpStatusCode.InsufficientStorage)
                }

                val written = withContext(Dispatchers.IO) {
                    // Labelled on purpose: with two nested `use` blocks a bare
                    // `return@use` binds to the INNER one, so the over-size signal
                    // was swallowed and an unbounded chunk answered 200 with
                    // received=0 instead of 413.
                    RandomAccessFile(part, "rw").use raf@{ raf ->
                        raf.seek(offset)
                        val buf = ByteArray(64 * 1024)
                        var n = 0L
                        call.receiveStream().use { input ->
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                // Never let a .part grow past its declared size.
                                if (n + read > MAX_CHUNK_BYTES || offset + n + read > total) {
                                    return@raf -1L
                                }
                                raf.write(buf, 0, read)
                                n += read
                            }
                        }
                        n
                    }
                }
                if (written < 0) return@post fail("chunk too large", HttpStatusCode.PayloadTooLarge)

                state.lastActivityMs = System.currentTimeMillis()
                val received = offset + written
                if (received >= total && written > 0) {
                    // An abandoned .part left over from a longer previous
                    // upload would otherwise survive as trailing garbage.
                    if (part.length() > total) {
                        withContext(Dispatchers.IO) {
                            RandomAccessFile(part, "rw").use { it.setLength(total) }
                        }
                    }
                    val final = File(store.dir, name)
                    if (!part.renameTo(final)) {
                        return@post fail("rename failed", HttpStatusCode.InternalServerError)
                    }
                    call.respondText(
                        "{\"ok\":true,\"received\":$received,\"complete\":true}",
                        ContentType.Application.Json,
                    )
                } else {
                    call.respondText(
                        "{\"ok\":true,\"received\":$received}",
                        ContentType.Application.Json,
                    )
                }
            }
            if (ran == null) fail("another request is using this file", HttpStatusCode.Conflict)
        }

        get("/books") {
            if (!authorize(state, pin)) return@get
            val json = store.list().joinToString(prefix = "[", postfix = "]") { b ->
                "{\"name\":\"${b.id}\",\"size\":${b.file.length()},\"modified\":${b.file.lastModified()}}"
            }
            call.respondText(json, ContentType.Application.Json)
        }

        delete("/book") {
            if (!authorize(state, pin)) return@delete
            val name = sanitizeName(call.request.queryParameters["name"])
                ?: return@delete fail("bad name", HttpStatusCode.BadRequest)
            val ran = state.withNameLock(name) {
                File(store.dir, name).delete()
                File(store.dir, "$name${BooksRepository.PART_SUFFIX}").delete()
                store.deletePosition(name)
            }
            if (ran == null) return@delete fail("another request is using this file", HttpStatusCode.Conflict)
            state.lastActivityMs = System.currentTimeMillis()
            call.respondText("{\"ok\":true}", ContentType.Application.Json)
        }

        // Cleanup path for an aborted upload: drops the .part only, so a
        // failed transfer leaves no dead weight and never touches a good book.
        delete("/part") {
            if (!authorize(state, pin)) return@delete
            val name = sanitizeName(call.request.queryParameters["name"])
                ?: return@delete fail("bad name", HttpStatusCode.BadRequest)
            val ran = state.withNameLock(name) {
                File(store.dir, "$name${BooksRepository.PART_SUFFIX}").delete()
            }
            if (ran == null) return@delete fail("another request is using this file", HttpStatusCode.Conflict)
            state.lastActivityMs = System.currentTimeMillis()
            call.respondText("{\"ok\":true}", ContentType.Application.Json)
        }
    }
}

/**
 * Rejects the call unless the PIN matches. A wrong PIN deliberately does NOT
 * count as activity, so a brute-forcer cannot hold the 2-minute idle stop
 * open. Wrong guesses are counted per source address ([PinGuard]): the tenth
 * locks that address out for five minutes (429) and leaves everyone else, and
 * the server itself, alone.
 *
 * A plain comparison is deliberate. The PIN is printed on the watch screen and
 * in its notification, and a timing side channel is orders of magnitude below
 * WiFi jitter - it is not the way this PIN gets discovered.
 */
private suspend fun RoutingContext.authorize(state: UploadState, pin: String): Boolean {
    val address = call.request.origin.remoteAddress
    val waitMs = state.pinGuard.lockedForMs(address)
    if (waitMs > 0) {
        call.response.headers.append(HttpHeaders.RetryAfter, ((waitMs + 999) / 1000).toString())
        call.respondText(
            "{\"error\":\"too many wrong PINs\"}",
            ContentType.Application.Json,
            HttpStatusCode.TooManyRequests,
        )
        return false
    }
    if (call.request.queryParameters["pin"].orEmpty() == pin) return true
    state.pinGuard.recordFailure(address)
    call.respondText(
        "{\"error\":\"bad pin\"}",
        ContentType.Application.Json,
        HttpStatusCode.Unauthorized,
    )
    return false
}

private suspend fun RoutingContext.fail(message: String, status: HttpStatusCode) {
    call.respondText("{\"error\":\"$message\"}", ContentType.Application.Json, status)
}

/**
 * Names are *rejected*, not stripped: silently deleting characters turned
 * "Björn.m4b" into "Bjrn.m4b" and could collide two books onto one file.
 * Separators, dot-segments, control chars and quotes (the JSON above is
 * hand-rolled) are the only things that must not get through.
 */
private fun sanitizeName(raw: String?): String? {
    val name = raw?.trim().orEmpty()
    if (name.isEmpty()) return null
    // ext4 caps a file name at 255 *bytes*, so a limit in UTF-16 chars would
    // let 180 emoji through and fail the open with ENAMETOOLONG.
    if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return null
    if (name.startsWith(".") || name.contains("..")) return null
    if (name.any { it == '/' || it == '\\' || it == '"' || it.code < 0x20 || it.code == 0x7F }) return null
    if (name.endsWith(BooksRepository.PART_SUFFIX)) return null
    if (name.substringBeforeLast('.', "").isBlank()) return null
    return if (BooksRepository.isSupportedName(name)) name else null
}

private val UPLOADER_HTML = """
<!doctype html>
<html><head><meta charset="utf-8"><title>Aloud upload</title></head>
<body style="font-family:sans-serif;max-width:40rem;margin:2rem auto">
<h2>Aloud &mdash; add audiobooks</h2>
<p><label>PIN from watch: <input id="pin" inputmode="numeric" size="8" autocomplete="off"></label></p>
<input type="file" id="f" multiple>
<ul id="s"></ul>
<script>
var CHUNK = 1 << 20, TRIES = 3;
var pinEl = document.getElementById('pin'), fEl = document.getElementById('f'), sEl = document.getElementById('s');
pinEl.value = new URLSearchParams(location.search).get('pin') || '';
function q(name, pin, extra){
  return 'name=' + encodeURIComponent(name) + '&pin=' + encodeURIComponent(pin) + (extra || '');
}
async function up(file, pin){
  var li = document.createElement('li'); li.textContent = file.name; sEl.appendChild(li);
  var total = file.size;
  for(var off = 0; off < total; off += CHUNK){
    var end = Math.min(off + CHUNK, total);
    var ok = false, why = '';
    for(var t = 0; t < TRIES && !ok; t++){
      if(t) await new Promise(function(r){ setTimeout(r, 500 * t); });
      try{
        var r = await fetch('book?' + q(file.name, pin, '&offset=' + off + '&total=' + total),
          {method:'POST', body:file.slice(off, end)});
        if(r.ok) ok = true; else why = 'HTTP ' + r.status + ' ' + (await r.text());
      }catch(e){ why = e.message; }
    }
    // A failed chunk ABORTS the file. Skipping ahead (the old behaviour) left a
    // hole in the middle and still renamed the result into the library.
    if(!ok){
      li.textContent = file.name + ' \u2014 FAILED at ' + off + '/' + total + ' (' + why + ') \u2014 aborted, nothing added';
      try{ await fetch('part?' + q(file.name, pin), {method:'DELETE'}); }catch(e){}
      return false;
    }
    li.textContent = file.name + ' \u2014 ' + Math.round(end / total * 100) + '%';
  }
  li.textContent = file.name + ' \u2014 done';
  return true;
}
fEl.onchange = async function(e){
  var pin = pinEl.value.trim();
  if(!pin){ alert('Enter the PIN shown on the watch first.'); return; }
  for(var i = 0; i < e.target.files.length; i++){
    if(!await up(e.target.files[i], pin)) break;
  }
};
</script></body></html>
"""
