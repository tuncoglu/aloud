package com.emre.wearbook.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.emre.wearbook.R
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Foreground service (dataSync) hosting the upload server, so long uploads
 * survive the Activity being closed. Started from the Uploader screen —
 * the user-tap start keeps it FGS-at-start compliant.
 */
class UploadServerService : Service() {

    private var server: UploadServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Uploads", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name) + " uploads")
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        server = UploadServer(this)
        server?.start(PORT)
        running.value = true
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        running.value = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.emre.wearbook.upload.START"
        private const val ACTION_STOP = "com.emre.wearbook.upload.STOP"
        private const val CHANNEL_ID = "wearbook_uploads"
        const val PORT = 8080

        val running = MutableStateFlow(false)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, UploadServerService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, UploadServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
