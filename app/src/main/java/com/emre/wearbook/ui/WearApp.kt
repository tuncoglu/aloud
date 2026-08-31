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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.emre.wearbook.books.BooksRepository
import com.emre.wearbook.data.PlayerPrefs
import com.emre.wearbook.playback.PlayerManager
import com.emre.wearbook.upload.UploadServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        if (startUploader) UploadServerService.start(context)
    }

    LaunchedEffect(autoplayBookId) {
        if (autoplayBookId != null) {
            BooksRepository.bookByName(context, autoplayBookId)?.let {
                manager.playBook(it)
                screen = Screen.NowPlaying
            }
        } else {
            // continue-listening: reopen the last book, paused at its position.
            // It is prepared, not playing — the user decides when to resume.
            val lastId = withContext(Dispatchers.IO) { PlayerPrefs.getLastBook(context) }
            val last = lastId?.let { BooksRepository.bookByName(context, it) }
            if (last != null) {
                manager.prepareBook(last)
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

    when (screen) {
        Screen.Library -> LibraryScreen(
            onPlay = {
                requestNotificationPermission()
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
