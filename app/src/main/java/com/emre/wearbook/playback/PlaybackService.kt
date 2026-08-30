package com.emre.wearbook.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var playerManager: PlayerManager? = null

    override fun onCreate() {
        super.onCreate()
        playerManager = PlayerManager.get(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        playerManager?.session

    override fun onDestroy() {
        // Only release when the service is really being torn down —
        // the player is recreated lazily if the app is relaunched.
        playerManager?.release()
        playerManager = null
        super.onDestroy()
    }
}
