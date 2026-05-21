package com.powerchina.zhixun.xiaozhi

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
    private val _requests = MutableSharedFlow<OpenConversationRequest>(extraBufferCapacity = 1)
    val requests: SharedFlow<OpenConversationRequest> = _requests.asSharedFlow()

    @Volatile
    var pendingAutoConnect: Boolean = false
        private set

    fun requestOpenConversation(autoConnect: Boolean = false, fromVoiceWake: Boolean = false) {
        if (autoConnect || fromVoiceWake) pendingAutoConnect = true
        _requests.tryEmit(
            OpenConversationRequest(
                autoConnect = autoConnect,
                fromVoiceWake = fromVoiceWake,
            ),
        )
    }

    fun consumeAutoConnect(): Boolean {
        if (!pendingAutoConnect) return false
        pendingAutoConnect = false
        return true
    }
}
