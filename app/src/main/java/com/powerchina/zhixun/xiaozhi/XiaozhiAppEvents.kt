package com.powerchina.zhixun.xiaozhi

import android.util.Log

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class OpenConversationRequest(
    val autoConnect: Boolean = false,
    val fromVoiceWake: Boolean = false,
)

data class PhotoResult(
    val file: java.io.File?,
    val uploadResult: Result<Unit>,
    /** HTTP 视觉接口返回的描述文本（已解析，非原始 JSON） */
    val visionDescription: String? = null,
)

/**
 * 小智 UI 与后台会话之间的事件通道。
 */
object XiaozhiAppEvents {
    private const val TAG = "AppEvents"

    private val _requests = MutableSharedFlow<OpenConversationRequest>(extraBufferCapacity = 1)
    val requests: SharedFlow<OpenConversationRequest> = _requests.asSharedFlow()

    private val _photoCaptureRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val photoCaptureRequests: SharedFlow<Unit> = _photoCaptureRequests.asSharedFlow()

    private val _photoShareRequests = MutableSharedFlow<java.io.File>(extraBufferCapacity = 1)
    val photoShareRequests: SharedFlow<java.io.File> = _photoShareRequests.asSharedFlow()

    private val _photoResults = MutableSharedFlow<PhotoResult>(
        replay = 1,
        extraBufferCapacity = 1,
    )
    val photoResults: SharedFlow<PhotoResult> = _photoResults.asSharedFlow()

    @Volatile
    var pendingAutoConnect: Boolean = false
        private set

    /** 拍照上传/等待小智视觉回复期间，禁止恢复后台唤醒监听 */
    @Volatile
    var photoSessionActive: Boolean = false
        private set

    fun beginPhotoSession() {
        photoSessionActive = true
        Log.i(TAG, "beginPhotoSession")
    }

    fun endPhotoSession() {
        if (!photoSessionActive) return
        photoSessionActive = false
        Log.i(TAG, "endPhotoSession")
    }

    fun isPhotoSessionActive(): Boolean = photoSessionActive

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

    fun requestPhotoCapture() {
        val emitted = _photoCaptureRequests.tryEmit(Unit)
        Log.i(TAG, "requestPhotoCapture emitted=$emitted")
    }

    fun sharePhotoToChat(photoFile: java.io.File) {
        val emitted = _photoShareRequests.tryEmit(photoFile)
        Log.i(TAG, "sharePhotoToChat file=${photoFile.name} emitted=$emitted")
    }

    fun emitPhotoResult(result: PhotoResult) {
        val emitted = _photoResults.tryEmit(result)
        Log.i(
            TAG,
            "emitPhotoResult file=${result.file?.name} success=${result.uploadResult.isSuccess} emitted=$emitted",
        )
    }

    fun consumeAutoConnect(): Boolean {
        if (!pendingAutoConnect) return false
        pendingAutoConnect = false
        Log.d(TAG, "consumeAutoConnect=true")
        return true
    }
}
