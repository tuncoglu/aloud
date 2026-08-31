package com.emre.wearbook.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import com.emre.wearbook.books.BooksRepository
import com.emre.wearbook.data.PlayerPrefs
import com.emre.wearbook.playback.ControllerPlaybackUi
import com.emre.wearbook.playback.PlaybackState
import com.emre.wearbook.playback.PlaybackUi
import com.emre.wearbook.upload.UploadServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Screen {
    data object Library : Screen
    data object NowPlaying : Screen
    data object Chapters : Screen
    data object Uploader : Screen
}

@Composable
fun WearApp(
    controller: MediaController?,
    autoplayBookId: String? = null,
    initialScreen: String? = null,
    startUploader: Boolean = false,
) {
    val context = LocalContext.current
    // The UI drives playback exclusively through the MediaController — never
    // through the player object the service owns. Rebuilds when the controller
    // connection settles; until then the buttons simply are not interactive.
    val ui: PlaybackUi? = remember(controller) { controller?.let(::ControllerPlaybackUi) }
    var screen by remember {
        mutableStateOf(
            when (initialScreen) {
                "nowplaying" -> Screen.NowPlaying
                "uploader" -> Screen.Uploader
                else -> Screen.Library
            },
        )
    }

    LaunchedEffect(startUploader) {
        if (startUploader) UploadServerService.start(context)
    }

    LaunchedEffect(autoplayBookId, ui) {
        if (ui == null) return@LaunchedEffect
        if (autoplayBookId != null) {
            BooksRepository.bookByName(context, autoplayBookId)?.let {
                ui.playBook(it)
                screen = Screen.NowPlaying
            }
        } else {
            // continue-listening: reopen the last book, paused at its position.
            // It is prepared, not playing — the user decides when to resume.
            val lastId = withContext(Dispatchers.IO) { PlayerPrefs.getLastBook(context) }
            val last = lastId?.let { BooksRepository.bookByName(context, it) }
            if (last != null) {
                ui.prepareBook(last)
                screen = Screen.NowPlaying
            }
        }
    }

    // R15: the media notification needs POST_NOTIFICATIONS on API 33+; requesting
    // it only from the uploader screen used to suppress media controls until the
    // user happened to open the uploader.
    val notificationPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPerm.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    val uiOr: PlaybackUi = ui ?: return
    when (screen) {
        Screen.Library -> {
            val scope = rememberCoroutineScope()
            LibraryScreen(
                onPlay = {
                    requestNotificationPermission()
                    uiOr.playBook(it)
                    screen = Screen.NowPlaying
                },
                onOpenUploader = { screen = Screen.Uploader },
                onDelete = { book ->
                    scope.launch {
                        // Stop playback if the deleted book is the one playing,
                        // then drop the file and its saved position.
                        if (PlaybackState.nowPlaying.value?.id == book.id) uiOr.stop()
                        book.file.delete()
                        PlayerPrefs.deletePos(context, book.id)
                    }
                },
            )
        }
        Screen.NowPlaying -> {
            BackHandler { screen = Screen.Library }
            NowPlayingScreen(uiOr, onOpenChapters = { screen = Screen.Chapters })
        }
        Screen.Chapters -> {
            BackHandler { screen = Screen.NowPlaying }
            ChapterScreen(uiOr)
        }
        Screen.Uploader -> {
            BackHandler { screen = Screen.Library }
            UploaderScreen()
        }
    }
}
