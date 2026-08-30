package com.emre.wearbook.playback

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaParserExtractorAdapter
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.session.MediaSession
import com.emre.wearbook.MainActivity
import com.emre.wearbook.books.Book
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

data class ChapterUi(val startMs: Long, val endMs: Long, val title: String)

/**
 * Single instance shared by the Activity and PlaybackService (same process).
 * Owns the ExoPlayer + MediaSession and exposes UI state as StateFlows.
 */
@OptIn(UnstableApi::class)
class PlayerManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // MediaParser-based extraction: the classic Mp4Extractor does not parse MP4
    // chapter atoms; chapter support lives on the platform MediaParser path.
    private val mediaSourceFactory = ProgressiveMediaSource.Factory(
        DefaultDataSource.Factory(context),
        MediaParserExtractorAdapter.Factory(),
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
        .build()

    val session: MediaSession = MediaSession.Builder(context, player)
        .setSessionActivity(
            PendingIntent.getActivity(
                context, 0,
                android.content.Intent(context, MainActivity::class.java),
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
    private var ticksSinceSave = 0

    init {
        // Restore persisted speed + sleep timer, wire listeners.
        scope.launch {
            val savedSpeed = PlayerPrefs.getSpeed(context)
            player.setPlaybackSpeed(savedSpeed)
            speed.value = savedSpeed
            val savedSleepEnd = PlayerPrefs.getSleepEndMs(context)?.takeIf { it > System.currentTimeMillis() }
            sleepEndMs.value = savedSleepEnd
            if (savedSleepEnd != null) startSleepMonitor() // re-arm after process/service restart
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                android.util.Log.d("WearBite", "onIsPlayingChanged: $playing")
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
                android.util.Log.d("WearBite", "onMediaItemTransition: ${mediaItem?.mediaId} reason=$reason")
                nowPlaying.value = mediaItem?.let {
                    com.emre.wearbook.books.BooksRepository.bookByName(context, it.mediaId)
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
                android.util.Log.d(
                    "WearBite",
                    "onTracksChanged, audioFormat.metadata entries: ${fmtMetadata?.length() ?: 0}",
                )
                fmtMetadata?.let { chapters.value = extractChapters(it) }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                val dur = player.duration.takeIf { it > 0 }
                if (dur != null && dur != durationMs.value) durationMs.value = dur
            }
        })
    }

    /** Play a book, resuming from its persisted position. */
    fun playBook(book: Book) {
        scope.launch {
            val saved = PlayerPrefs.getPos(context, book.id)
            android.util.Log.d("WearBite", "playBook '${book.id}' resume at $saved ms")
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
                android.util.Log.d("WearBite", "book was finished, restarting from 0")
                player.seekTo(0)
            }
            player.play()
            PlayerPrefs.setLastBook(context, book.id)

            // M4B chapters: parse directly from the file (Media3's MP4 path does
            // not surface them on this device).
            if (book.file.extension.lowercase() == "m4b") {
                scope.launch(Dispatchers.IO) {
                    val parsed = com.emre.wearbook.books.Mp4ChapterParser.parse(book.file)
                    android.util.Log.d("WearBite", "Mp4ChapterParser: ${parsed.size} chapters: " +
                        parsed.take(3).joinToString { "${it.startMs}ms '${it.title}'" })
                    if (parsed.isNotEmpty()) {
                        chapters.value = parsed.map { c ->
                            ChapterUi(c.startMs, c.endMs, c.title)
                        }
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun skipToChapter(index: Int) {
        chapters.value.getOrNull(index)?.let { player.seekTo(it.startMs + chapterOffset) }
    }

    /** Chapters are Period-relative; with a single media period the offset is 0. */
    private val chapterOffset: Long = 0L

    fun setSpeed(newSpeed: Float) {
        speed.value = newSpeed
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
        scope.launch {
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
        android.util.Log.d("WearBite", "extractChapters: ${result.size} chapters: ${result.joinToString { "${it.startMs}ms '${it.title}'" }}")
        return result
    }

    fun release() {
        positionJob?.cancel()
        positionJob = null
        runBlocking { persister() } // final position save on service teardown
        player.release()
        session.release()
        scope.cancel()
    }

    companion object {
        @Volatile
        private var instance: PlayerManager? = null

        fun get(context: Context): PlayerManager =
            instance ?: synchronized(this) {
                instance ?: PlayerManager(context.applicationContext).also { instance = it }
            }
    }
}
