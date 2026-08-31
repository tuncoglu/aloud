package com.emre.wearbook.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.emre.wearbook.MainActivity
import com.emre.wearbook.books.Book
import com.emre.wearbook.books.BooksRepository
import com.emre.wearbook.books.Mp4ChapterParser
import com.emre.wearbook.data.PlayerPrefs
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "WearBite"

typealias ChapterUi = Mp4ChapterParser.Chapter

/** Index of the chapter containing [positionMs], or -1 before the first chapter. */
fun List<ChapterUi>.chapterIndexAt(positionMs: Long): Int =
    indexOfLast { it.startMs <= positionMs }

/** Session custom-command names and args, shared by the UI (MediaController)
 *  and the service (PlayerManager). */
object PlaybackCommand {
    const val PLAY_BOOK = "wearbite.play_book"
    const val SET_SLEEP = "wearbite.sleep"
    const val ARG_MEDIA_ID = "mediaId"
    const val ARG_AUTOPLAY = "autoplay"
    const val ARG_MINUTES = "minutes"
    const val MINUTES_OFF = -1
}

/**
 * Service-side owner of ExoPlayer + MediaSession. Instantiated by
 * PlaybackService only — the UI never holds it. UI state lands in
 * [PlaybackState], which the UI reads; the UI drives the player through its
 * MediaController and the custom commands in [PlaybackCommand].
 *
 * Note the import: this is androidx.annotation.OptIn, not Kotlin's. Media3's
 * marker is enforced by androidx.annotation.experimental, which kotlin.OptIn
 * does not satisfy — it silently had no effect and left 19 lint errors
 * standing. Annotating the class @UnstableApi instead would only push the
 * requirement onto every caller.
 */
@OptIn(UnstableApi::class)
class PlayerManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mediaSourceFactory = ProgressiveMediaSource.Factory(
        DefaultDataSource.Factory(context),
        DefaultExtractorsFactory(),
    )

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        .setWakeMode(C.WAKE_MODE_LOCAL)
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
        .setCallback(@OptIn(UnstableApi::class) object : MediaSession.Callback {
            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
                PlaybackCommand.PLAY_BOOK -> {
                    val id = args.getString(PlaybackCommand.ARG_MEDIA_ID)
                    val book = id?.let { BooksRepository.bookByName(context, it) }
                    if (book != null) {
                        applyBook(book, autoPlay = args.getBoolean(PlaybackCommand.ARG_AUTOPLAY, true))
                        ImmediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        ImmediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }

                PlaybackCommand.SET_SLEEP -> {
                    setSleepTimer(
                        args.getInt(PlaybackCommand.ARG_MINUTES, PlaybackCommand.MINUTES_OFF)
                            .takeIf { it > 0 },
                    )
                    ImmediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        })
        .build()

    private var positionJob: Job? = null
    private var chapterJob: Job? = null
    private var sleepJob: Job? = null
    private var ticksSinceSave = 0

    private val chapterCache = ConcurrentHashMap<String, List<ChapterUi>>()

    init {
        scope.launch {
            val savedSpeed = PlayerPrefs.getSpeed(context)
            player.setPlaybackSpeed(savedSpeed)
            PlaybackState.speed.value = savedSpeed
            val savedSleepEnd = PlayerPrefs.getSleepEndMs(context)?.takeIf { it > System.currentTimeMillis() }
            PlaybackState.sleepEndMs.value = savedSleepEnd
            if (savedSleepEnd == null) {
                PlayerPrefs.setSleepEndMs(context, null)
                PlayerPrefs.setSleepMinutes(context, null)
            } else {
                PlaybackState.sleepMinutes.value = PlayerPrefs.getSleepMinutes(context) ?: 15
                startSleepMonitor()
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.d(TAG, "playerError: ${error.errorCodeName} (${error.message})")
                PlaybackState.playbackError.value = error.errorCodeName
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: $playing")
                PlaybackState.isPlaying.value = playing
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
                chapterJob?.cancel()
                chapterJob = null
                PlaybackState.playbackError.value = null
                PlaybackState.nowPlaying.value = mediaItem?.let {
                    BooksRepository.bookByName(context, it.mediaId)
                }
                PlaybackState.durationMs.value =
                    mediaItem?.let { player.duration.takeIf { d -> d > 0 } ?: 0L } ?: 0L
                PlaybackState.chapters.value = emptyList()
            }

            @UnstableApi
            override fun onMetadata(metadata: Metadata) {
                extractChapters(metadata).takeIf { it.isNotEmpty() }?.let {
                    PlaybackState.chapters.value = it
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val fmtMetadata = player.audioFormat?.metadata
                Log.d(
                    TAG,
                    "onTracksChanged, audioFormat.metadata entries: ${fmtMetadata?.length() ?: 0}",
                )
                fmtMetadata?.let {
                    extractChapters(it).takeIf { c -> c.isNotEmpty() }?.let { c ->
                        PlaybackState.chapters.value = c
                    }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                val dur = player.duration.takeIf { it > 0 }
                if (dur != null && dur != PlaybackState.durationMs.value) {
                    PlaybackState.durationMs.value = dur
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                PlaybackState.positionMs.value = newPosition.positionMs
                savePositionNow()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                PlaybackState.speed.value = playbackParameters.speed
                // Speed is set through the MediaController now, so persistence
                // happens here instead of in a UI-facing setter.
                scope.launch { PlayerPrefs.setSpeed(context, playbackParameters.speed) }
            }
        })
    }

    /** Opens a book by its library id; [autoPlay] decides play vs prepare. */
    private fun applyBook(book: Book, autoPlay: Boolean) {
        savePositionNow()
        PlaybackState.playbackError.value = null
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
            withTimeoutOrNull(5_000) {
                while (player.duration <= 0) delay(100)
            }
            if (saved > 0 && player.duration > 0 && saved >= player.duration - 2_000) {
                Log.d(TAG, "book was finished, restarting from 0")
                player.seekTo(0)
            }
            if (autoPlay) player.play()
            PlayerPrefs.setLastBook(context, book.id)

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
                    if (parsed.isNotEmpty() && player.currentMediaItem?.mediaId == book.id) {
                        PlaybackState.chapters.value = parsed
                    }
                }
            }
        }
    }

    private fun startSleepMonitor() {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            while (isActive) {
                val end = PlaybackState.sleepEndMs.value ?: return@launch
                if (System.currentTimeMillis() >= end) {
                    player.pause()
                    PlayerPrefs.setSleepEndMs(context, null)
                    PlayerPrefs.setSleepMinutes(context, null)
                    PlaybackState.sleepEndMs.value = null
                    PlaybackState.sleepMinutes.value = null
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
                    PlaybackState.positionMs.value = player.currentPosition
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

    private fun positionSnapshot(): Pair<String, Long>? {
        val id = player.currentMediaItem?.mediaId ?: return null
        val pos = player.currentPosition
        return if (pos > 0) id to pos else null
    }

    private fun savePositionNow() {
        val (bookId, posMs) = positionSnapshot() ?: return
        scope.launch { PlayerPrefs.setPos(context, bookId, posMs) }
    }

    /** null = off. [minutes] from now; persisted so a service restart re-arms it. */
    private fun setSleepTimer(minutes: Int?) {
        scope.launch {
            val end = minutes?.let { System.currentTimeMillis() + it * 60_000L }
            PlayerPrefs.setSleepEndMs(context, end)
            PlayerPrefs.setSleepMinutes(context, minutes)
            PlaybackState.sleepEndMs.value = end
            PlaybackState.sleepMinutes.value = minutes
            startSleepMonitor()
        }
    }

    private fun extractChapters(metadata: Metadata): List<ChapterUi> {
        val chapters: List<Chapter> = metadata.getEntriesOfType(Chapter::class.java).toList()
        return chapters.mapIndexedNotNull { i, c ->
            if (c.isHidden) null
            else ChapterUi(
                startMs = c.startTimeMs,
                endMs = c.endTimeMs,
                title = c.title?.value ?: "Chapter ${i + 1}",
            )
        }
    }

    fun release() {
        positionJob?.cancel()
        positionJob = null
        chapterJob?.cancel()
        chapterJob = null
        positionSnapshot()?.let { (bookId, posMs) ->
            runBlocking {
                withTimeoutOrNull(500) { PlayerPrefs.setPos(context, bookId, posMs) }
            }
        }
        player.release()
        session.release()
        scope.cancel()
    }
}

/**
 * The guava artifact Media3 references is the empty "ListenableFuture only"
 * one, so immediate futures have to be provided by hand instead of through
 * com.google.common.util.concurrent.Futures.
 */
private class ImmediateFuture<T>(private val value: T) : ListenableFuture<T> {
    override fun addListener(listener: Runnable, executor: Executor): Unit = executor.execute(listener)
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = true
    override fun get(): T = value
    override fun get(timeout: Long, unit: TimeUnit): T = value
}
