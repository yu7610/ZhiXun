package com.powerchina.zhixun.xiaozhi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class OpenConversationRequest(
    val autoConnect: Boolean = false,
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

    fun requestOpenConversation(autoConnect: Boolean = false) {
        if (autoConnect) pendingAutoConnect = true
        _requests.tryEmit(OpenConversationRequest(autoConnect = autoConnect))
    }

    fun consumeAutoConnect(): Boolean {
        if (!pendingAutoConnect) return false
        pendingAutoConnect = false
        return true
    }
}
