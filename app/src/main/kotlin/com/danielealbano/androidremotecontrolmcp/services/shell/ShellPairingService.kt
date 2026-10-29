package com.danielealbano.androidremotecontrolmcp.services.shell

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
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a pairing attempt stands; the set-up page and the notification both show it. */
enum class PairPhase {
    IDLE,

    /** Waiting for the phone's "pair with a code" page to appear. */
    SEARCHING,

    /** The page is up: the six digits are wanted. */
    CODE_WANTED,
    WORKING,
    WRONG_CODE,
    FAILED,
    DONE,
}

/** The pairing attempt in progress, for the set-up page to watch. */
object ShellPairing {
    private val state = MutableStateFlow(PairPhase.IDLE)
    val phase: StateFlow<PairPhase> = state

    internal fun set(phase: PairPhase) {
        state.value = phase
    }
}

/** The words of the pairing notification, and what counts as a code. Pure. */
object PairNotice {
    const val CHANNEL_NAME = "无线调试配对"
    const val REPLY_LABEL = "6 位配对码"
    const val REPLY_ACTION = "填配对码"

    fun title(phase: PairPhase): String =
        when (phase) {
            PairPhase.CODE_WANTED -> "看到 6 位配对码了吗？填在这里"
            PairPhase.WORKING -> "正在配对…"
            PairPhase.WRONG_CODE -> "配对码不对，再填一次"
            PairPhase.FAILED -> "没配上，再试一次"
            PairPhase.DONE -> "配对好了"
            else -> "等你打开配对码页面"
        }

    fun text(phase: PairPhase): String =
        when (phase) {
            PairPhase.CODE_WANTED, PairPhase.WRONG_CODE -> "点下面的「$REPLY_ACTION」，把屏幕上的 6 位数字填进来。先别离开那个页面。"
            PairPhase.WORKING -> "马上就好。"
            PairPhase.FAILED -> "配对码页面可能已经关了。重新点「使用配对码配对设备」，再填一次。"
            PairPhase.DONE -> "点这里回到应用。"
            else -> "在「无线调试」里点「使用配对码配对设备」。出现 6 位数字后，回到这条通知来填。"
        }

    /** The six digits out of whatever was typed (people add spaces), or null when it is not a code. */
    fun code(typed: CharSequence?): String? {
        val digits = typed?.filter { it.isDigit() }?.toString()
        return digits?.takeIf { it.length == CODE_DIGITS }
    }

    /** Whether the notification offers the input field in [phase]. */
    fun asksForCode(phase: PairPhase): Boolean = phase == PairPhase.CODE_WANTED || phase == PairPhase.WRONG_CODE

    private const val CODE_DIGITS = 6
}

/**
 * Lives for the few minutes of a pairing: the user is away in the system's settings, where the
 * six-digit code is shown only while that page stays open, so the code is typed into this
 * service's notification. A foreground service, because a process without one is frozen within
 * seconds of leaving the screen and would never see the pairing page announce itself.
 */
@AndroidEntryPoint
class ShellPairingService : Service() {
    @Inject
    lateinit var bridge: ShellBridge

    @Inject
    lateinit var discovery: AdbDiscovery

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var port: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_REPLY -> reply(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE))
            else -> finish()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        show(PairPhase.SEARCHING, foreground = true)
        if (watching != null) return
        watching =
            scope.launch {
                launch {
                    delay(LIFETIME_MS)
                    finish()
                }
                discovery.ports(AdbDiscovery.TLS_PAIRING).collect { found ->
                    port = found
                    // A result on screen stays until the page comes or goes again.
                    if (ShellPairing.phase.value != PairPhase.WORKING) {
                        show(if (found == null) PairPhase.SEARCHING else PairPhase.CODE_WANTED)
                    }
                }
            }
    }

    private fun reply(typed: CharSequence?) {
        val code = PairNotice.code(typed)
        val target = port
        if (code == null || target == null) {
            show(if (target == null) PairPhase.FAILED else PairPhase.WRONG_CODE)
            return
        }
        show(PairPhase.WORKING)
        scope.launch {
            when (bridge.pair(target, code)) {
                PairOutcome.PAIRED -> {
                    show(PairPhase.DONE)
                    finish(keepNotice = true)
                }

                PairOutcome.WRONG_CODE -> {
                    show(PairPhase.WRONG_CODE)
                }

                PairOutcome.FAILED -> {
                    show(PairPhase.FAILED)
                }
            }
        }
    }

    private fun show(
        phase: PairPhase,
        foreground: Boolean = false,
    ) {
        ShellPairing.set(phase)
        val notice = notification(phase)
        if (foreground) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notice, type) }
                .onFailure { Log.w(TAG, "could not go to the foreground: ${it.javaClass.simpleName}") }
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notice)
        }
    }

    private fun notification(phase: PairPhase): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, PairNotice.CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
        )
        val open =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, SettingsRoute.Shell.route)
        val text = PairNotice.text(phase)
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(PairNotice.title(phase))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(phase != PairPhase.DONE)
                .setAutoCancel(phase == PairPhase.DONE)
                .setOnlyAlertOnce(phase == PairPhase.WORKING)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, IMMUTABLE))
        if (PairNotice.asksForCode(phase)) builder.addAction(replyAction())
        return builder.build()
    }

    private fun replyAction(): NotificationCompat.Action {
        val intent = Intent(this, ShellPairingService::class.java).setAction(ACTION_REPLY)
        // Mutable: the system writes what the user typed into this intent.
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getService(this, 1, intent, flags)
        val input = RemoteInput.Builder(KEY_CODE).setLabel(PairNotice.REPLY_LABEL).build()
        return NotificationCompat.Action
            .Builder(R.drawable.ic_notification, PairNotice.REPLY_ACTION, pending)
            .addRemoteInput(input)
            .build()
    }

    private fun finish(keepNotice: Boolean = false) {
        if (!keepNotice && ShellPairing.phase.value != PairPhase.DONE) ShellPairing.set(PairPhase.IDLE)
        val how = if (keepNotice) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE
        ServiceCompat.stopForeground(this, how)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MCP:Shell"
        private const val CHANNEL = "shell_pairing"
        private const val NOTIFICATION_ID = 4320
        private const val KEY_CODE = "code"
        private const val ACTION_START = "shell.pair.START"
        private const val ACTION_REPLY = "shell.pair.REPLY"
        private const val LIFETIME_MS = 5 * 60_000L
        private const val IMMUTABLE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        fun start(context: Context) {
            val intent = Intent(context, ShellPairingService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "pairing service not allowed now: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShellPairingService::class.java))
        }
    }
}
