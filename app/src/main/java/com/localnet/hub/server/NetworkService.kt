package com.localnet.hub.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.localnet.hub.R
import com.localnet.hub.ui.MainActivity

class NetworkService : Service() {

    companion object {
        private const val TAG = "NetworkService"
        private const val CHANNEL_ID = "LocalNetHub"
        private const val NOTIF_ID = 1
        const val SERVER_PORT = 8080
    }

    val httpServer = LocalHttpServer(port = SERVER_PORT)
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): NetworkService = this@NetworkService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Server starting…"))
        httpServer.onUpdate = { updateNotification() }
        httpServer.start()
        Log.i(TAG, "NetworkService created — HTTP server on :$SERVER_PORT")
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        httpServer.stop()
        super.onDestroy()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val clients = httpServer.connectedClients.size
        val msgs = httpServer.messages.size
        nm.notify(NOTIF_ID, buildNotification("$clients device(s) • $msgs messages"))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalNet Hub Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LocalNet Hub",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Local network server status" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
