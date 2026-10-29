package com.danielealbano.androidremotecontrolmcp.services.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.R

/**
 * Foreground service hosting [ChatHttpServer] for the on-device chat and wake endpoints.
 */
class ChatHttpService : Service() {
    private var server: ChatHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
        try {
            val s = ChatHttpServer(ChatHttpServer.PORT)
            s.start()
            server = s
            Log.i(TAG, "Chat HTTP started on http://127.0.0.1:${ChatHttpServer.PORT}/")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start chat HTTP", e)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onDestroy() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch =
            NotificationChannel(
                getString(R.string.notification_channel_chat_http_id),
                getString(R.string.notification_channel_chat_http_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat
            .Builder(this, getString(R.string.notification_channel_chat_http_id))
            .setContentTitle(getString(R.string.notification_chat_http_title))
            .setContentText(getString(R.string.notification_chat_http_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "MCP:ChatHttpService"
        private const val NOTIF_ID = 8765

        /**
         * Starts the local server, on phones that have the root helper it fronts. On every other
         * phone nothing would ever call it: no service is started and no notification shown.
         */
        fun start(context: Context) {
            if (!RootHelper.present()) return
            val i = Intent(context, ChatHttpService::class.java)
            context.startForegroundService(i)
        }
    }
}
