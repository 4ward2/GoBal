package com.danielealbano.androidremotecontrolmcp.services.localmodel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.R

/**
 * Keeps the app in the foreground while the embedded model server runs, so Android does not
 * reclaim the app (and with it the server) the moment the user switches away. It holds no state:
 * [LocalModelServer] starts and stops it with the server.
 */
class LocalModelService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val name = getString(R.string.local_model_channel_name)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
        nm?.createNotificationChannel(channel)
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    private fun notification(): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.local_model_notification_title))
            .setContentText(getString(R.string.local_model_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "local_model_channel"
        private const val NOTIFICATION_ID = 8093

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LocalModelService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocalModelService::class.java))
        }
    }
}
