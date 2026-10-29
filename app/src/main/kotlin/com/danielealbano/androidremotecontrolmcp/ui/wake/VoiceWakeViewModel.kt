package com.danielealbano.androidremotecontrolmcp.ui.wake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Activity 路径（锁屏 / 息屏唤醒 / 尚未授麦克风）的宿主。
 *
 * 流程本体在 [VoiceWakeSession]，解锁态的悬浮窗路径共用同一个实现，
 * 这里只负责把它挂到 `viewModelScope` 上。
 */
@HiltViewModel
class VoiceWakeViewModel
    @Inject
    constructor(
        private val session: VoiceWakeSession,
    ) : ViewModel() {
        val ui = session.ui
        val level = session.level

        fun missingMic() = session.missingMic()

        fun begin() = session.begin(viewModelScope)

        fun dismiss() = session.dismiss()

        fun cancelListen() = session.cancelListen()
    }
