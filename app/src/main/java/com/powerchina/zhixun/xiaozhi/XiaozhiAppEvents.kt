package com.powerchina.zhixun.xiaozhi

import android.util.Log

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class OpenConversationRequest(
    val autoConnect: Boolean = false,
    val fromVoiceWake: Boolean = false,
)

/**
 * 小智 UI 与后台会话之间的事件通道。
 */
object XiaozhiAppEvents {
    private const val TAG = "AppEvents"

    private val _requests = MutableSharedFlow<OpenConversationRequest>(extraBufferCapacity = 1)
    val requests: SharedFlow<OpenConversationRequest> = _requests.asSharedFlow()

    @Volatile
    var pendingAutoConnect: Boolean = false
        private set

    fun requestOpenConversation(autoConnect: Boolean = false, fromVoiceWake: Boolean = false) {
        if (autoConnect || fromVoiceWake) pendingAutoConnect = true
        val emitted = _requests.tryEmit(
            OpenConversationRequest(
                autoConnect = autoConnect,
                fromVoiceWake = fromVoiceWake,
            ),
        )
        Log.i(
            TAG,
            "requestOpenConversation autoConnect=$autoConnect wake=$fromVoiceWake emitted=$emitted",
        )
    }

    fun consumeAutoConnect(): Boolean {
        if (!pendingAutoConnect) return false
        pendingAutoConnect = false
        Log.d(TAG, "consumeAutoConnect=true")
        return true
    }
}
