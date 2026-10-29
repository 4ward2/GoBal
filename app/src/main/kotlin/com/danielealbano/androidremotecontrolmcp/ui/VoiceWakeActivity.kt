package com.danielealbano.androidremotecontrolmcp.ui

import android.Manifest
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatHttpService
import com.danielealbano.androidremotecontrolmcp.services.wake.VoiceWakeOverlayService
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.wake.VoiceWakeScreen
import com.danielealbano.androidremotecontrolmcp.ui.wake.VoiceWakeViewModel
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint

/** Full-screen translucent wake overlay launched by Magisk long-press POWER. */
@AndroidEntryPoint
class VoiceWakeActivity : ComponentActivity() {
    private val viewModel: VoiceWakeViewModel by viewModels()

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.begin()
            } else {
                viewModel.missingMic()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockscreen()
        ChatHttpService.start(applicationContext)

        // 解锁态交给穿透式悬浮窗，Activity 立刻退场 —— 只要 Activity 还在，
        // 系统垫在它下面的 ActivityRecordInputSink 就会吃掉所有触摸，
        // 下层应用一点都收不到（实测过，给窗口加 FLAG_NOT_TOUCHABLE 也没用）。
        if (shouldDelegateToOverlay()) {
            VoiceWakeOverlayService.start(applicationContext)
            finish()
            // 主题本身是全透明，这里只需去掉退场动画，避免闪一下。
            // overridePendingTransition 在 34 起已废弃，用新 API。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            }
            return
        }

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            AndroidRemoteControlMcpTheme(dynamicColor = false) {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val level by viewModel.level.collectAsStateWithLifecycle()
                LaunchedEffect(ui.shouldFinish) {
                    if (ui.shouldFinish) finish()
                }
                VoiceWakeScreen(
                    ui = ui,
                    level = level,
                    onDismiss = {
                        viewModel.dismiss()
                        finish()
                    },
                )
            }
        }

        if (PermissionUtils.isMicrophonePermissionGranted(this)) {
            viewModel.begin()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        viewModel.cancelListen()
        super.onDestroy()
    }

    /**
     * 只有「已解锁 + 能画悬浮窗 + 已授麦克风」时才走悬浮窗。
     *
     * - 锁屏/息屏唤醒：`TYPE_APPLICATION_OVERLAY` 的层级在锁屏之下，辉光会被挡住，
     *   所以这种场景仍用 Activity（那时屏幕上本来也没东西可点）。
     * - 没授麦克风：申请权限需要 Activity，悬浮窗弹不出系统授权框。
     */
    private fun shouldDelegateToOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (!PermissionUtils.isMicrophonePermissionGranted(this)) return false
        val keyguard = getSystemService(KeyguardManager::class.java)
        return keyguard?.isKeyguardLocked == false
    }

    private fun showOverLockscreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT < 27) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }
}
