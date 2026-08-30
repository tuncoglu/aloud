package com.emre.wearbook.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Tracks
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.source.MediaParserExtractorAdapter
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
 */
@OptIn(UnstableApi::class)
class PlayerManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // M4B chapters ride the platform MediaParser path; classic extractors do
    // not parse MP4 chapter atoms. The MediaParser MP3 extractor seeks badly
    // backwards on this device, so MP3s get the classic Mp3Extractor instead.
    private val m4bSourceFactory = ProgressiveMediaSource.Factory(
        DefaultDataSource.Factory(context),
        MediaParserExtractorAdapter.Factory(),
    )
    private val mp3SourceFactory = ProgressiveMediaSource.Factory(
        DefaultDataSource.Factory(context),
        DefaultExtractorsFactory(),
    )

    private fun sourceFactoryFor(book: Book) =
        if (book.file.extension.lowercase() == "m4b") m4bSourceFactory else mp3SourceFactory

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(m4bSourceFactory)
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
            if (savedSleepEnd != null) startSleepMonitor() // re-arm after process/service restart
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: $playing")
                isPlaying.value = playing
                if (playing) {
                    startPositionTicker()
                } else {
                    positionJob?.cancel()
                    positionJob = null
                    scope.launch { persister() }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d(TAG, "onMediaItemTransition: ${mediaItem?.mediaId} reason=$reason")
                // Any in-flight chapter parse belongs to the previous item.
                chapterJob?.cancel()
                chapterJob = null
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
                chapters.value = extractChapters(metadata)
            }

            override fun onTracksChanged(tracks: Tracks) {
                // MP4 QuickTime chapters are attached to the audio track's Format
                // metadata (Mp4Extractor outputs the format with chapter entries).
                val fmtMetadata = player.audioFormat?.metadata
                Log.d(
                    TAG,
                    "onTracksChanged, audioFormat.metadata entries: ${fmtMetadata?.length() ?: 0}",
                )
                fmtMetadata?.let { chapters.value = extractChapters(it) }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                val dur = player.duration.takeIf { it > 0 }
                if (dur != null && dur != durationMs.value) durationMs.value = dur
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed.value = playbackParameters.speed
            }
        })
    }

    /** Play a book, resuming from its persisted position. */
    fun playBook(book: Book) {
        scope.launch {
            val saved = PlayerPrefs.getPos(context, book.id)
            Log.d(TAG, "playBook '${book.id}' resume at $saved ms")
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
            player.setMediaSource(sourceFactoryFor(book).createMediaSource(item), saved.coerceAtLeast(0))
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
            player.play()
            PlayerPrefs.setLastBook(context, book.id)

            // M4B chapters: parse directly from the file (Media3's MP4 path does
            // not surface them on this device). Cached per file version: the file
            // is immutable while a book exists, a re-upload rewrites it.
            if (book.file.extension.lowercase() == "m4b") {
                chapterJob?.cancel()
                chapterJob = scope.launch(Dispatchers.IO) {
                    val key = "${book.id}|${book.file.length()}|${book.file.lastModified()}"
                    val parsed = chapterCache[key] ?: Mp4ChapterParser.parse(book.file)
                        .also { chapterCache[key] = it }
                    Log.d(TAG, "Mp4ChapterParser: ${parsed.size} chapters: " +
                        parsed.take(3).joinToString { "${it.startMs}ms '${it.title}'" })
                    // Only publish if this book is still current — a slow parse
                    // must not clobber the book the user already switched to.
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
            sleepEndMs.value = end
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
                    sleepEndMs.value = null
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
                    if (++ticksSinceSave >= 5) {
                        ticksSinceSave = 0
                        persister()
                    }
                    delay(1_000)
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun persister() {
        nowPlaying.value?.let { book ->
            player.currentPosition.takeIf { it > 0 }?.let { pos ->
                PlayerPrefs.setPos(context, book.id, pos)
            }
        }
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
        runBlocking { persister() } // final position save on service teardown
        player.release()
        session.release()
        scope.cancel()
        clear(this)
    }

    companion object {
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
