package com.emre.wearbook

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.emre.wearbook.playback.PlaybackService
import com.emre.wearbook.ui.WearApp

class MainActivity : ComponentActivity() {

    /** Binds the PlaybackService so it can promote to a foreground service
     *  while playing — the binding (not startForegroundService) is what Media3
     *  expects; its notification manager handles the FGS promotion on playback. */
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                // The activity may be gone before the future resolves: cancelled
                // futures must not be read, and nothing may leak a live controller.
                controller = runCatching { future.get() }.getOrNull()
            },
            androidx.core.content.ContextCompat.getMainExecutor(this),
        )
        // Debug hooks:
        //   --es autoplay <bookId>   play a book without UI taps
        //   --es screen <name>       start on a specific screen (library/nowplaying/uploader)
        //   --es uploader_start 1    also start the upload server immediately (perms pre-granted via pm)
        val autoplay = intent.getStringExtra("autoplay")
        val screen = intent.getStringExtra("screen")
        val uploaderStart = intent.getStringExtra("uploader_start") == "1"
        setContent {
            WearApp(autoplayBookId = autoplay, initialScreen = screen, startUploader = uploaderStart)
        }
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        // releaseFuture cancels the pending build too: without it, an activity
        // destroyed before the controller resolved leaked a live binding.
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }
}
