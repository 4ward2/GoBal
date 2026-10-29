package com.danielealbano.androidremotecontrolmcp.services.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity

/** The words of the notification that says the app stays connected. Pure. */
object OnlineNotice {
    const val TITLE = "保持在线"

    /** Says what is being kept and why, so the user can judge whether they want it. */
    fun text(labels: List<String>): String {
        val named = labels.take(SHOWN).joinToString("、")
        val more = if (labels.size > SHOWN) " 等 ${labels.size} 个" else ""
        return "和 $named$more 保持连接，用来收消息和待你批准的请求。可在「我的电脑」里关掉。"
    }

    private const val SHOWN = 2
}

/**
 * Exists only while the app is asked to stay connected without being on screen: it is what
 * tells the system (and, through its notification, the user) that the process is in use.
 * It does no work itself; the connections belong to [ConnectionManager].
 */
class OnlineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val labels = intent?.getStringArrayListExtra(EXTRA_LABELS).orEmpty()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(labels), type) }
            .onFailure { Log.w(TAG, "could not go to the foreground: ${it.javaClass.simpleName}") }
        // Restarted by the system with no intent, it has nothing to show: the manager starts it again when wanted.
        if (intent == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(labels: List<String>): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, OnlineNotice.TITLE, NotificationManager.IMPORTANCE_MIN)
        manager.createNotificationChannel(channel.apply { setShowBadge(false) })
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val text = OnlineNotice.text(labels)
        return NotificationCompat
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(OnlineNotice.TITLE)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        private const val TAG = "MCP:Link"
        private const val CHANNEL = "link_online"
        private const val NOTIFICATION_ID = 4310
        private const val EXTRA_LABELS = "labels"

        fun start(
            context: Context,
            labels: List<String>,
        ) {
            val intent = Intent(context, OnlineService::class.java)
            intent.putStringArrayListExtra(EXTRA_LABELS, ArrayList(labels))
            // The system refuses a foreground service started while the app is unseen and not
            // exempt from battery optimisation; the connections live on, only without its cover.
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "foreground service not allowed now: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OnlineService::class.java))
        }
    }
}
