package com.danielealbano.androidremotecontrolmcp.services.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The words a task's notification shows. Pure. */
object AgentNotices {
    private const val SHOWN_STEPS = 4

    fun title(run: AgentRun): String =
        when (run.status) {
            RunStatus.RUNNING -> "正在操作手机"
            RunStatus.WAITING_APPROVAL -> "等你允许后继续"
            RunStatus.DONE -> "做完了"
            RunStatus.FAILED -> "没有做成"
            RunStatus.STOPPED -> "已停止"
            RunStatus.HANDED_OVER -> "需要你接手"
        }

    /** While it runs: "第 3 步：点「搜索」"; afterwards: what the user is told. */
    fun text(run: AgentRun): String {
        val last = run.steps.lastOrNull()
        return when {
            run.status.finished -> run.result.ifBlank { run.goal }
            last == null -> run.goal
            else -> "第 ${run.steps.size} 步：${last.label}"
        }
    }

    /** The request and the latest steps, one per line, for the expanded notification. */
    fun detail(run: AgentRun): String {
        val from = (run.steps.size - SHOWN_STEPS).coerceAtLeast(0)
        val steps = run.steps.drop(from).mapIndexed { i, step -> "${from + i + 1}. ${step.label}" }
        return (listOf(run.goal) + steps).joinToString("\n")
    }
}

/** Builds the task's notifications: the ongoing one with its three buttons, and the result. */
internal class TaskNotifications(
    private val context: Context,
) {
    private fun base(channel: String): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp())

    fun idle(): Notification = base(CHANNEL_PROGRESS).setContentTitle("手机操作").build()

    fun progress(run: AgentRun): Notification {
        val reply = RemoteInput.Builder(AgentActionReceiver.KEY_NOTE).setLabel("补充一句").build()
        val add =
            NotificationCompat.Action
                .Builder(0, "补充", action(AgentActionReceiver.ACTION_NOTE, mutable = true))
                .addRemoteInput(reply)
                .build()
        return base(CHANNEL_PROGRESS)
            .setContentTitle(AgentNotices.title(run))
            .setContentText(AgentNotices.text(run))
            .setStyle(NotificationCompat.BigTextStyle().bigText(AgentNotices.detail(run)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", action(AgentActionReceiver.ACTION_STOP, mutable = false))
            .addAction(0, "交给我", action(AgentActionReceiver.ACTION_TAKE_OVER, mutable = false))
            .addAction(add)
            .build()
    }

    fun result(run: AgentRun): Notification =
        base(CHANNEL_RESULT)
            .setContentTitle(AgentNotices.title(run))
            .setContentText(AgentNotices.text(run))
            .setStyle(NotificationCompat.BigTextStyle().bigText(AgentNotices.text(run)))
            .setAutoCancel(true)
            .build()

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** A notification button; the reply action must be mutable for the system to add the typed text. */
    private fun action(
        name: String,
        mutable: Boolean,
    ): PendingIntent {
        val intent = Intent(context, AgentActionReceiver::class.java).setAction(name)
        val flags = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, name.hashCode(), intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_PROGRESS = "agent_progress"
        const val CHANNEL_RESULT = "agent_result"
    }
}

/**
 * Keeps the app alive while a phone task runs (its first step usually sends the app to the
 * background) and shows the task in a notification the user can act on: stop, take over, or
 * add a sentence. Ends by itself when the task does, leaving the result as a notification.
 */
@AndroidEntryPoint
class AgentService : Service() {
    @Inject lateinit var runner: AgentRunner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notices by lazy { TaskNotifications(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        goForeground()
        scope.launch {
            runner.runs.current.collect { run ->
                when {
                    run == null || run.status.finished -> finishWith(run)
                    else -> manager()?.notify(PROGRESS_ID, notices.progress(run))
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Every start must be answered with startForeground, also when the service is still up.
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val current = runner.runs.current.value
        val run = current?.takeUnless { it.status.finished }
        startForeground(PROGRESS_ID, if (run != null) notices.progress(run) else notices.idle())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finishWith(run: AgentRun?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (run != null) manager()?.notify(RESULT_ID, notices.result(run))
        stopSelf()
    }

    private fun manager(): NotificationManager? = getSystemService(NotificationManager::class.java)

    private fun ensureChannels() {
        val progress =
            NotificationChannel(TaskNotifications.CHANNEL_PROGRESS, "手机操作进度", NotificationManager.IMPORTANCE_LOW)
        val results =
            NotificationChannel(TaskNotifications.CHANNEL_RESULT, "手机操作结果", NotificationManager.IMPORTANCE_HIGH)
        manager()?.createNotificationChannels(listOf(progress, results))
    }

    companion object {
        private const val PROGRESS_ID = 8771
        private const val RESULT_ID = 8772

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentService::class.java))
        }
    }
}

/** The buttons on the task notification. */
@AndroidEntryPoint
class AgentActionReceiver : BroadcastReceiver() {
    @Inject lateinit var runner: AgentRunner

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_NOTE)
        when (intent.action) {
            ACTION_STOP -> runner.stop()
            ACTION_TAKE_OVER -> runner.takeOver()
            ACTION_NOTE -> typed?.let { runner.supplement(it.toString()) }
        }
    }

    companion object {
        const val ACTION_STOP = "com.danielealbano.androidremotecontrolmcp.agent.STOP"
        const val ACTION_TAKE_OVER = "com.danielealbano.androidremotecontrolmcp.agent.TAKE_OVER"
        const val ACTION_NOTE = "com.danielealbano.androidremotecontrolmcp.agent.NOTE"
        const val KEY_NOTE = "note"
    }
}
