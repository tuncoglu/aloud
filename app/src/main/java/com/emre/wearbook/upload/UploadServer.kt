package com.emre.wearbook.upload

import android.content.Context
import com.emre.wearbook.books.BooksRepository
import com.emre.wearbook.data.PlayerPrefs
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
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
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Tiny embedded HTTP uploader: PC browser -> http://<watch-ip>:8080.
 * Endpoint contract (no multipart on purpose):
 *   GET    /                        -> uploader HTML page
 *   POST   /book?name=&offset=&total= -> raw bytes appended to books/<name>.part
 *                                        (renamed to <name> when offset+len==total)
 *   GET    /books                   -> JSON list
 *   DELETE /book?name=              -> delete file + its progress positions
 */
class UploadServer(
    private val context: Context,
    private val onEvent: (String) -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var autoStopJob: Job? = null
    @Volatile private var lastActivityMs = System.currentTimeMillis()

    val isRunning: Boolean get() = server != null

    fun start(port: Int = 8080) {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.start(wait = false)
        startAutoStop()
        onEvent("server started on $port")
    }

    fun stop() {
        autoStopJob?.cancel()
        autoStopJob = null
        server?.stop(0, 0)
        server = null
        onEvent("server stopped")
    }

    private fun Application.module() {
        routing {
            get("/") {
                call.respondText(UPLOADER_HTML, ContentType.Text.Html)
            }

            post("/book") {
                val name = sanitizeName(call.request.queryParameters["name"])
                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                val total = call.request.queryParameters["total"]?.toLongOrNull() ?: -1L
                if (name == null) {
                    call.respondText("{\"error\":\"bad name\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                val bytes = call.receiveStream().readBytes()
                val part = File(BooksRepository.booksDir(context), "$name.part")
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(offset)
                    raf.write(bytes)
                }
                lastActivityMs = System.currentTimeMillis()
                val received = offset + bytes.size
                if (total > 0 && received >= total && bytes.isNotEmpty()) {
                    val final = part.resolveSibling(name)
                    part.renameTo(final)
                    onEvent("uploaded: $name")
                    call.respondText("{\"ok\":true,\"received\":$received,\"complete\":true}", ContentType.Application.Json)
                } else {
                    call.respondText("{\"ok\":true,\"received\":$received}", ContentType.Application.Json)
                }
            }

            get("/books") {
                val list = BooksRepository.list(context)
                val json = list.joinToString(prefix = "[", postfix = "]") { b ->
                    "{\"name\":\"${b.id}\",\"size\":${b.file.length()},\"modified\":${b.file.lastModified()}}"
                }
                call.respondText(json, ContentType.Application.Json)
            }

            delete("/book") {
                val name = sanitizeName(call.request.queryParameters["name"])
                if (name != null) {
                    File(BooksRepository.booksDir(context), name).delete()
                    File(BooksRepository.booksDir(context), "$name.part").delete()
                    runBlocking { PlayerPrefs.deletePos(context, name) }
                    lastActivityMs = System.currentTimeMillis()
                    call.respondText("{\"ok\":true}", ContentType.Application.Json)
                } else {
                    call.respondText("{\"error\":\"bad name\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
            }
        }
    }

    private fun sanitizeName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._ -]"), "").trim()
        if (cleaned.contains("..") || cleaned.endsWith(".part")) return null
        return when (cleaned.substringAfterLast('.', "").lowercase()) {
            "mp3", "m4b" -> cleaned
            else -> null
        }
    }

    private fun startAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            while (isActive) {
                if (System.currentTimeMillis() - lastActivityMs > 2 * 60_000L) {
                    stop()
                    return@launch
                }
                delay(10_000)
            }
        }
    }

    companion object {
        /** First non-loopback IPv4 of the watch, for display in the app. */
        fun watchIpv4(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}

private val UPLOADER_HTML = """
<!doctype html>
<html><head><meta charset="utf-8"><title>WearBite upload</title></head>
<body style="font-family:sans-serif;max-width:40rem;margin:2rem auto">
<h2>WearBite — add audiobooks</h2>
<input type="file" id="f" multiple>
<ul id="s"></ul>
<script>
const CHUNK = 1 << 20;
async function up(file){
  const li = document.createElement('li'); li.textContent = file.name; s.appendChild(li);
  const total = file.size;
  for(let off = 0; off < total; off += CHUNK){
    const body = file.slice(off, Math.min(off + CHUNK, total));
    const r = await fetch('book?name=' + encodeURIComponent(file.name) + '&offset=' + off + '&total=' + total,
      {method:'POST', body:body});
    if(!r.ok){ li.textContent = file.name + ' — upload failed at ' + off; continue; }
    li.textContent = file.name + ' — ' + Math.min(off + CHUNK, total) + '/' + total;
  }
  li.textContent = file.name + ' — done';
}
f.onchange = async e => { for(const file of e.target.files) await up(file); };
</script></body></html>
"""
