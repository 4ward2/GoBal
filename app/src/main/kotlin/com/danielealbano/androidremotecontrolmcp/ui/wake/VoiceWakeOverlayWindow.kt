package com.danielealbano.androidremotecontrolmcp.ui.wake

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

/**
 * 把唤醒辉光挂成一个 `TYPE_APPLICATION_OVERLAY` 窗口。
 *
 * 为什么不能沿用 Activity：Android 13+ 会在每个 Activity 下面垫一层
 * `ActivityRecordInputSink`（`touchableRegion` 是整屏级、且与窗口大小无关），
 * 专门吃掉漏下去的触摸以防点击劫持。实测即便给 Activity 窗口加
 * `FLAG_NOT_TOUCHABLE`，下层应用依然收不到任何点击。
 * 悬浮窗不创建 Activity，也就没有这层 sink，下层应用还保持 RESUMED。
 *
 * ComposeView 不在 Activity 里，必须自带 Lifecycle / ViewModelStore /
 * SavedStateRegistry 三个 owner，否则 setContent 会抛
 * "ViewTreeLifecycleOwner not found"。
 */
class VoiceWakeOverlayWindow(
    private val context: Context,
) : LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var view: ComposeView? = null

    private val windowManager: WindowManager
        get() = context.getSystemService(WindowManager::class.java)

    fun show(session: VoiceWakeSession) {
        if (view != null) return
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        val composeView =
            ComposeView(context).apply {
                setViewTreeLifecycleOwner(this@VoiceWakeOverlayWindow)
                setViewTreeViewModelStoreOwner(this@VoiceWakeOverlayWindow)
                setViewTreeSavedStateRegistryOwner(this@VoiceWakeOverlayWindow)
                setContent {
                    AndroidRemoteControlMcpTheme(dynamicColor = false) {
                        val ui by session.ui.collectAsStateWithLifecycle()
                        val level by session.level.collectAsStateWithLifecycle()
                        VoiceWakeScreen(
                            ui = ui,
                            level = level,
                            // 悬浮窗整窗不接触摸，点按无从谈起；同时去掉全屏压暗，
                            // 只留辉光本身，观感上尽量接近「没有遮罩」。
                            onDismiss = null,
                            chromeless = true,
                        )
                    }
                }
            }
        runCatching { windowManager.addView(composeView, buildParams()) }
            .onSuccess { view = composeView }
            .onFailure { lifecycleRegistry.currentState = Lifecycle.State.DESTROYED }
    }

    fun dismiss() {
        val current = view ?: return
        view = null
        runCatching { windowManager.removeViewImmediate(current) }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_TOUCHABLE 让触摸整块落到下层应用；NOT_FOCUSABLE 保证输入法/按键
                // 仍归下层；LAYOUT_NO_LIMITS + cutout ALWAYS 让辉光铺到刘海和圆角之外。
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        return params
    }
}
