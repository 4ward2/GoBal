package com.danielealbano.androidremotecontrolmcp.services.wake

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.danielealbano.androidremotecontrolmcp.ui.wake.VoiceWakeOverlayWindow
import com.danielealbano.androidremotecontrolmcp.ui.wake.VoiceWakeSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

/**
 * 解锁态的唤醒宿主：把辉光挂成穿透式悬浮窗，语音流程也在这里跑。
 *
 * Activity 不能承担这个角色 —— 见 [VoiceWakeOverlayWindow] 里关于
 * `ActivityRecordInputSink` 的说明。进程存活靠已在前台的 ChatHttpService，
 * 所以这里不需要再起一个前台通知。
 */
@AndroidEntryPoint
class VoiceWakeOverlayService : Service() {
    @Inject
    lateinit var sessions: Provider<VoiceWakeSession>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: VoiceWakeOverlayWindow? = null
    private var session: VoiceWakeSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (overlay == null) start()
        return START_NOT_STICKY
    }

    private fun start() {
        val current = sessions.get()
        session = current
        overlay = VoiceWakeOverlayWindow(this).also { it.show(current) }
        current.begin(scope)
        scope.launch {
            current.ui.first { it.shouldFinish }
            stopSelf()
        }
        // 兜底：悬浮窗不接触摸，用户没法点掉它。万一听写/网络卡住，
        // 到点也必须自己消失，不能在屏幕上挂着不走。
        scope.launch {
            delay(MAX_LIFETIME_MS)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 必须显式取消：listenOnce 在 IO 线程里是阻塞读，光 cancel 协程作用域
        // 打断不了它，得靠 isCancelled 回调让录音循环自己退出。
        session?.cancelListen()
        session = null
        overlay?.dismiss()
        overlay = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_LIFETIME_MS = 45_000L

        fun start(context: Context) {
            context.startService(Intent(context, VoiceWakeOverlayService::class.java))
        }
    }
}
