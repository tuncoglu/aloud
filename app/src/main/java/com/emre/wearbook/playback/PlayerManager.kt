package com.emre.wearbook.playback

import android.annotation.SuppressLint
import android.app.PendingIntent
import androidx.annotation.OptIn
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Tracks
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.session.MediaSession
import com.emre.wearbook.MainActivity
import com.emre.wearbook.books.Book
import com.emre.wearbook.books.BooksRepository
import com.emre.wearbook.books.Mp4ChapterParser
import com.emre.wearbook.data.PlayerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "WearBite"

typealias ChapterUi = Mp4ChapterParser.Chapter

/** Index of the chapter containing [positionMs], or -1 before the first chapter. */
fun List<ChapterUi>.chapterIndexAt(positionMs: Long): Int =
    indexOfLast { it.startMs <= positionMs }

/**
 * Single instance shared by the Activity and PlaybackService (same process).
 * Owns the ExoPlayer + MediaSession and exposes UI state as StateFlows.
 *
 * Note the import: this is androidx.annotation.OptIn, not Kotlin's. Media3's
 * marker is enforced by androidx.annotation.experimental, which kotlin.OptIn
 * does not satisfy — it silently had no effect and left 19 lint errors
 * standing. Annotating the class @UnstableApi instead would only push the
 * requirement onto every caller.
 */
@OptIn(UnstableApi::class)
class PlayerManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Classic extractors for both formats: M4B chapters come via
    // Mp4ChapterParser (MediaParser never delivered them on this device), and
    // the MediaParser MP3 extractor seeks badly backwards here.
    private val mediaSourceFactory = ProgressiveMediaSource.Factory(
        DefaultDataSource.Factory(context),
        DefaultExtractorsFactory(),
    )

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        .setWakeMode(C.WAKE_MODE_LOCAL)
        // Same ±30s jump for the app buttons and the system/BT media keys.
        .setSeekBackIncrementMs(30_000)
        .setSeekForwardIncrementMs(30_000)
        .build()

    val session: MediaSession = MediaSession.Builder(context, player)
        .setSessionActivity(
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    // --- UI state ---
    val nowPlaying = MutableStateFlow<Book?>(null)
    val isPlaying = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val speed = MutableStateFlow(1.0f)
    val chapters = MutableStateFlow<List<ChapterUi>>(emptyList())
    val sleepEndMs = MutableStateFlow<Long?>(null)
    /** Armed sleep-timer option (15/30/60/120), kept across re-arms and restarts. */
    val sleepMinutes = MutableStateFlow<Int?>(null)
    /** Last playback error to show on the now-playing screen; null = fine. */
    val playbackError = MutableStateFlow<String?>(null)

    private var positionJob: Job? = null
    private var chapterJob: Job? = null
    private var sleepJob: Job? = null
    private var ticksSinceSave = 0

    // Chapter lists are parsed from disk once per file version, not per play.
    private val chapterCache = ConcurrentHashMap<String, List<ChapterUi>>()

    init {
        // Restore persisted speed + sleep timer, wire listeners.
        scope.launch {
            val savedSpeed = PlayerPrefs.getSpeed(context)
            player.setPlaybackSpeed(savedSpeed)
            val savedSleepEnd = PlayerPrefs.getSleepEndMs(context)?.takeIf { it > System.currentTimeMillis() }
            sleepEndMs.value = savedSleepEnd
            if (savedSleepEnd == null) {
                // An expired timer used to be filtered here and then linger in
                // DataStore for ever instead of being cleaned up.
                PlayerPrefs.setSleepEndMs(context, null)
                PlayerPrefs.setSleepMinutes(context, null)
            } else {
                // Re-arm after process/service restart; keep the armed option
                // with the timer so the chip cycle stays correct mid-countdown.
                sleepMinutes.value = PlayerPrefs.getSleepMinutes(context) ?: 15
                startSleepMonitor()
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.d(TAG, "playerError: ${error.errorCodeName} (${error.message})")
                playbackError.value = error.errorCodeName
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: $playing")
                isPlaying.value = playing
                if (playing) {
                    startPositionTicker()
                } else {
                    positionJob?.cancel()
                    positionJob = null
                    savePositionNow()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d(TAG, "onMediaItemTransition: ${mediaItem?.mediaId} reason=$reason")
                // Any in-flight chapter parse belongs to the previous item.
                chapterJob?.cancel()
                chapterJob = null
                playbackError.value = null
                nowPlaying.value = mediaItem?.let {
                    BooksRepository.bookByName(context, it.mediaId)
                }
                durationMs.value = mediaItem?.let { player.duration.takeIf { d -> d > 0 } ?: 0L } ?: 0L
                chapters.value = emptyList()
            }

            @UnstableApi
            override fun onMetadata(metadata: Metadata) {
                // Sample-level metadata (ID3/EMSG). MP4 chapters don't come through
                // here — they ride on the audio track Format, see onTracksChanged.
                // An empty result must not clobber a chapter list that
                // Mp4ChapterParser already produced for this book.
                extractChapters(metadata).takeIf { it.isNotEmpty() }?.let { chapters.value = it }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // MP4 QuickTime chapters are attached to the audio track's Format
                // metadata (Mp4Extractor outputs the format with chapter entries).
                val fmtMetadata = player.audioFormat?.metadata
                Log.d(
                    TAG,
                    "onTracksChanged, audioFormat.metadata entries: ${fmtMetadata?.length() ?: 0}",
                )
                fmtMetadata?.let {
                    extractChapters(it).takeIf { c -> c.isNotEmpty() }?.let { c -> chapters.value = c }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                val dur = player.duration.takeIf { it > 0 }
                if (dur != null && dur != durationMs.value) durationMs.value = dur
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // The ticker only runs while playing, so a seek on a paused
                // book used to leave the position text and the chapter
                // highlight stale until playback resumed.
                positionMs.value = newPosition.positionMs
                savePositionNow()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed.value = playbackParameters.speed
            }
        })
    }

    /** Play a book, resuming from its persisted position. */
    fun playBook(book: Book) = applyBook(book, autoPlay = true)

    /** Select the book (resuming its position) but stay paused. */
    fun prepareBook(book: Book) = applyBook(book, autoPlay = false)

    private fun applyBook(book: Book, autoPlay: Boolean) {
        // Flush the outgoing book's position while the player still refers to
        // it: setMediaItem below swaps both the media id and the position, and
        // a save that straddles the swap can file one book's position under the
        // other book's key.
        savePositionNow()
        playbackError.value = null
        scope.launch {
            val saved = PlayerPrefs.getPos(context, book.id)
            Log.d(TAG, "applyBook '${book.id}' resume at $saved ms (play: $autoPlay)")
            val item = MediaItem.Builder()
                .setMediaId(book.id)
                .setUri(Uri.fromFile(book.file))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.title)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                        .build(),
                )
                .build()
            player.setMediaItem(item, saved.coerceAtLeast(0))
            player.prepare()
            // Finished book: restart from the beginning instead of hanging at EOF.
            // Duration is only known async, so wait briefly for it.
            withTimeoutOrNull(5_000) {
                while (player.duration <= 0) delay(100)
            }
            if (saved > 0 && player.duration > 0 && saved >= player.duration - 2_000) {
                Log.d(TAG, "book was finished, restarting from 0")
                player.seekTo(0)
            }
            if (autoPlay) player.play()
            PlayerPrefs.setLastBook(context, book.id)

            // M4B chapters: parse directly from the file (Media3's MP4 path does
            // not surface them on this device). Cached per file version: the file
            // is immutable while a book exists, a re-upload rewrites it.
            if (book.file.extension.lowercase() == "m4b") {
                chapterJob?.cancel()
                chapterJob = scope.launch {
                    val parsed = withContext(Dispatchers.IO) {
                        val key = "${book.id}|${book.file.length()}|${book.file.lastModified()}"
                        chapterCache[key] ?: Mp4ChapterParser.parse(book.file)
                            .also { chapterCache[key] = it }
                    }
                    Log.d(TAG, "Mp4ChapterParser: ${parsed.size} chapters: " +
                        parsed.take(3).joinToString { "${it.startMs}ms '${it.title}'" })
                    // Only publish if this book is still current — a slow parse
                    // must not clobber the book the user already switched to.
                    // (Player access is main-thread-only, so this runs here.)
                    if (parsed.isNotEmpty() && player.currentMediaItem?.mediaId == book.id) {
                        chapters.value = parsed
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun skipToChapter(index: Int) {
        chapters.value.getOrNull(index)?.let { player.seekTo(it.startMs) }
    }

    /** ±[deltaMs] skip for chapterless books, clamped to the track. */
    fun skipRelative(deltaMs: Long) {
        val max = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, max)
        Log.d(TAG, "skipRelative($deltaMs) -> $target ms")
        player.seekTo(target)
    }

    fun setSpeed(newSpeed: Float) {
        player.setPlaybackSpeed(newSpeed)
        scope.launch { PlayerPrefs.setSpeed(context, newSpeed) }
    }

    /** null = off. [minutes] from now; persisted so a service restart re-arms it. */
    fun setSleepTimer(minutes: Int?) {
        scope.launch {
            val end = minutes?.let { System.currentTimeMillis() + it * 60_000L }
            PlayerPrefs.setSleepEndMs(context, end)
            PlayerPrefs.setSleepMinutes(context, minutes)
            sleepEndMs.value = end
            sleepMinutes.value = minutes
            startSleepMonitor()
        }
    }

    private fun startSleepMonitor() {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            while (isActive) {
                val end = sleepEndMs.value ?: return@launch
                if (System.currentTimeMillis() >= end) {
                    player.pause()
                    PlayerPrefs.setSleepEndMs(context, null)
                    PlayerPrefs.setSleepMinutes(context, null)
                    sleepEndMs.value = null
                    sleepMinutes.value = null
                    return@launch
                }
                delay(1_000)
            }
        }
    }

    private fun startPositionTicker() {
        positionJob?.cancel()
        positionJob = scope.launch {
            try {
                while (isActive) {
                    positionMs.value = player.currentPosition
                    // Every 20 s, not every 5 s: this rewrites the whole
                    // DataStore file, thousands of times per long book.
                    if (++ticksSinceSave >= 20) {
                        ticksSinceSave = 0
                        savePositionNow()
                    }
                    delay(1_000)
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Read the media id and the position from the player in one go, on the
     * main thread. The pair has to come from the same instant: the old code
     * took the book from a StateFlow (which lags a media-item transition) and
     * the position from the player, so switching books could persist the
     * incoming book's position under the outgoing book's key.
     */
    private fun positionSnapshot(): Pair<String, Long>? {
        val id = player.currentMediaItem?.mediaId ?: return null
        val pos = player.currentPosition
        return if (pos > 0) id to pos else null
    }

    /** Persist the current (book, position) pair; safe to call at any time. */
    private fun savePositionNow() {
        val (bookId, posMs) = positionSnapshot() ?: return
        scope.launch { PlayerPrefs.setPos(context, bookId, posMs) }
    }

    /**
     * MP4 chapter metadata arrives as parallel Chapter + Label entry streams
     * (they are separate entry types, so pair them positionally). Hidden
     * chapters are skipped; label-less chapters fall back to "Chapter N".
     */
    private fun extractChapters(metadata: Metadata): List<ChapterUi> {
        val chapters: List<Chapter> = metadata.getEntriesOfType(Chapter::class.java).toList()
        val result = chapters.mapIndexedNotNull { i, c ->
            if (c.isHidden) null
            else ChapterUi(
                startMs = c.startTimeMs,
                endMs = c.endTimeMs,
                title = c.title?.value ?: "Chapter ${i + 1}",
            )
        }
        Log.d(TAG, "extractChapters: ${result.size} chapters: ${result.joinToString { "${it.startMs}ms '${it.title}'" }}")
        return result
    }

    fun release() {
        positionJob?.cancel()
        positionJob = null
        chapterJob?.cancel()
        chapterJob = null
        // Final save on service teardown: snapshot first (the player is about
        // to go away), then block only briefly — this runs on the main thread.
        positionSnapshot()?.let { (bookId, posMs) ->
            runBlocking {
                withTimeoutOrNull(500) { PlayerPrefs.setPos(context, bookId, posMs) }
            }
        }
        player.release()
        session.release()
        scope.cancel()
        clear(this)
    }

    companion object {
        // Holds the application context only, so this is not an activity leak.
        // The singleton itself goes away with REMEDIATION.md R20, when the UI
        // starts driving playback through MediaController instead.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: PlayerManager? = null

        fun get(context: Context): PlayerManager =
            instance ?: synchronized(this) {
                instance ?: PlayerManager(context.applicationContext).also { instance = it }
            }

        /** Drop the process-wide singleton on service teardown, so a relaunch in
         *  a live process gets a fresh (never released) player. */
        fun clear(manager: PlayerManager) {
            synchronized(this) {
                if (instance === manager) instance = null
            }
        }
    }
}
