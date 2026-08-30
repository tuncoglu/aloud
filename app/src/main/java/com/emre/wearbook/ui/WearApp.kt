package com.emre.wearbook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.emre.wearbook.playback.PlayerManager

private sealed interface Screen {
    data object Library : Screen
    data object NowPlaying : Screen
    data object Chapters : Screen
    data object Uploader : Screen
}

@Composable
fun WearApp(autoplayBookId: String? = null, initialScreen: String? = null, startUploader: Boolean = false) {
    val context = LocalContext.current
    val manager = remember { PlayerManager.get(context) }
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
        if (startUploader) com.emre.wearbook.upload.UploadServerService.start(context)
    }

    LaunchedEffect(autoplayBookId) {
        if (autoplayBookId != null) {
            com.emre.wearbook.books.BooksRepository.bookByName(context, autoplayBookId)?.let {
                manager.playBook(it)
                screen = Screen.NowPlaying
            }
        }
    }

    when (screen) {
        Screen.Library -> LibraryScreen(
            onPlay = {
                manager.playBook(it)
                screen = Screen.NowPlaying
            },
            onOpenUploader = { screen = Screen.Uploader },
        )
        Screen.NowPlaying -> {
            BackHandler { screen = Screen.Library }
            NowPlayingScreen(manager, onOpenChapters = { screen = Screen.Chapters })
        }
        Screen.Chapters -> {
            BackHandler { screen = Screen.NowPlaying }
            ChapterScreen(manager)
        }
        Screen.Uploader -> {
            BackHandler { screen = Screen.Library }
            UploaderScreen()
        }
    }
}
