package com.powerchina.zhixun.dashcam

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 物理录像键事件（系统/OEM 广播）。
 */
object DashcamVideoKeyEvents {
    enum class KeyAction {
        /** keyCode=142 拍照 */
        PHOTO,
        /** keyCode=136 录像 */
        RECORD,
    }

    private val _events = MutableSharedFlow<KeyAction>(extraBufferCapacity = 1)
    val events: SharedFlow<KeyAction> = _events.asSharedFlow()

    fun emit(action: KeyAction) {
        val accepted = _events.tryEmit(action)
        Log.i(
            VideoKeyReceiver.TAG,
            "emit 按键事件: action=$action, 已投递=$accepted",
        )
    }
}
