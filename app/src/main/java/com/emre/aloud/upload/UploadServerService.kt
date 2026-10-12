package com.emre.aloud.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import com.emre.aloud.R
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address

/**
 * Foreground service (dataSync) hosting the upload server, so long uploads
 * survive the Activity being closed. Started from the Uploader screen —
 * the user-tap start keeps it FGS-at-start compliant.
 */
class UploadServerService : Service() {

    private var server: UploadServer? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /** Where the watch can be reached, as seen from the UI. */
    sealed interface Wifi {
        /** Asked for Wi-Fi; the radio may still be coming up. */
        data object Waiting : Wifi

        /** No saved network in range, so the request timed out. */
        data object Unavailable : Wifi
        data class Ready(val ip: String) : Wifi
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopSelf()
            // A restart with no action (or a null intent) must not leave a
            // foreground service running without ever calling startForeground().
            else -> if (server == null) stopSelf()
        }
        // The uploader is user-started and self-expiring: never resurrect it.
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (server != null) return
        val newPin = UploadServer.newPin()
        val channel = NotificationChannel(
            CHANNEL_ID, "Uploads", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name) + " uploads")
            .setContentText("PIN " + newPin)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        // The idle auto-stop tears the whole service down, so the notification
        // and the "Uploader on" UI can never outlive the running server.
        server = UploadServer(AndroidUploadStore(this), newPin, onAutoStop = { stopSelf() })
        server?.start(PORT)
        requestWifi()
        pin.value = newPin
        running.value = true
    }

    /**
     * Wear OS leaves the Wi-Fi radio off to save battery, and a foreground
     * service does not change that: a server that binds fine is then
     * unreachable. Holding a TRANSPORT_WIFI request is the documented way to
     * make the watch join a saved network and stay on it until the request is
     * released in [onDestroy]. The address shown on screen comes from that same
     * network, so it can no longer be some other interface's.
     *
     * Deliberately no bindProcessToNetwork(): the server only accepts inbound
     * connections and nothing else here needs routing.
     */
    private fun requestWifi() {
        wifi.value = Wifi.Waiting
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val ip = linkProperties.linkAddresses
                    .map { it.address }
                    .firstOrNull { it is Inet4Address }
                    ?.hostAddress
                wifi.value = if (ip != null) Wifi.Ready(ip) else Wifi.Waiting
            }

            override fun onLost(network: Network) {
                wifi.value = Wifi.Waiting
            }

            override fun onUnavailable() {
                wifi.value = Wifi.Unavailable
            }
        }
        wifiCallback = callback
        cm.requestNetwork(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            callback,
            WIFI_TIMEOUT_MS,
        )
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        wifiCallback?.let {
            // Releasing the request is what lets the radio power down again.
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        wifiCallback = null
        running.value = false
        pin.value = null
        wifi.value = Wifi.Waiting
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.emre.aloud.upload.START"
        private const val ACTION_STOP = "com.emre.aloud.upload.STOP"
        private const val CHANNEL_ID = "aloud_uploads"
        const val PORT = 8080
        private const val WIFI_TIMEOUT_MS = 20_000

        val running = MutableStateFlow(false)

        val wifi = MutableStateFlow<Wifi>(Wifi.Waiting)

        /** PIN of the currently running server, for display on the watch. */
        val pin = MutableStateFlow<String?>(null)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, UploadServerService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, UploadServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
