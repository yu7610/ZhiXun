package com.powerchina.zhixun.xiaozhi

import android.util.Log

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class OpenConversationRequest(
    val autoConnect: Boolean = false,
    val fromVoiceWake: Boolean = false,
    /** 物理录音键：连接成功后自动开麦 */
    val startVoiceOnConnect: Boolean = false,
)

data class PhotoResult(
    val file: java.io.File?,
    val uploadResult: Result<Unit>,
    /** HTTP 视觉接口返回的描述文本（已解析，非原始 JSON） */
    val visionDescription: String? = null,
    /** 拍照完成、识别尚未返回时先发预览 */
    val captureOnly: Boolean = false,
)

/**
 * 小智 UI 与后台会话之间的事件通道。
 */
object XiaozhiAppEvents {
    private const val TAG = "AppEvents"

    private val _requests = MutableSharedFlow<OpenConversationRequest>(
        replay = 1,
        extraBufferCapacity = 1,
    )
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

    /** 物理录音键待开麦（SharedFlow 丢失时兜底） */
    @Volatile
    var pendingVoiceKeyPress: Boolean = false
        private set

    /** 物理拍照键待处理（SharedFlow 丢失时兜底） */
    @Volatile
    var pendingPhotoKeyPress: Boolean = false
        private set

    @Volatile
    private var voiceKeyEpoch: Long = 0L

    @Volatile
    private var lastConsumedVoiceKeyEpoch: Long = -1L

    @Volatile
    private var photoKeyEpoch: Long = 0L

    @Volatile
    private var lastConsumedPhotoKeyEpoch: Long = -1L

    private val _photoKeyRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val photoKeyRequests: SharedFlow<Unit> = _photoKeyRequests.asSharedFlow()

    fun markPendingVoiceKeyPress() {
        pendingVoiceKeyPress = true
        Log.i(TAG, "markPendingVoiceKeyPress")
    }

    fun clearPendingVoiceKeyPress() {
        pendingVoiceKeyPress = false
    }

    fun hasPendingVoiceKeyPress(): Boolean = pendingVoiceKeyPress

    /**
     * 消费一次语音键事件。同一按压若被 NavHost / ViewModel 重复投递，仅第一次生效。
     */
    fun consumeVoiceKeyPressEvent(): Boolean {
        if (voiceKeyEpoch == lastConsumedVoiceKeyEpoch) {
            return false
        }
        lastConsumedVoiceKeyEpoch = voiceKeyEpoch
        pendingVoiceKeyPress = false
        return true
    }

    /** 结束对话等路径：同步 epoch，避免下一次按键被误判为重复 */
    fun acknowledgeVoiceKeyEvent() {
        lastConsumedVoiceKeyEpoch = voiceKeyEpoch
        pendingVoiceKeyPress = false
    }

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

    /** 对话页 Compose 是否在前台（用于拍照时避免重复拉起 Activity） */
    @Volatile
    var conversationScreenVisible: Boolean = false
        private set

    fun setConversationScreenVisible(visible: Boolean) {
        conversationScreenVisible = visible
    }

    fun requestOpenConversation(
        autoConnect: Boolean = false,
        fromVoiceWake: Boolean = false,
        startVoiceOnConnect: Boolean = false,
    ) {
        if (autoConnect || fromVoiceWake || startVoiceOnConnect) pendingAutoConnect = true
        val emitted = _requests.tryEmit(
            OpenConversationRequest(
                autoConnect = autoConnect,
                fromVoiceWake = fromVoiceWake,
                startVoiceOnConnect = startVoiceOnConnect,
            ),
        )
        Log.i(
            TAG,
            "requestOpenConversation autoConnect=$autoConnect wake=$fromVoiceWake " +
                "voiceKey=$startVoiceOnConnect emitted=$emitted",
        )
    }

    fun requestVoiceConversation() {
        voiceKeyEpoch++
        pendingVoiceKeyPress = true
        requestOpenConversation(autoConnect = true, startVoiceOnConnect = true)
    }

    fun requestPhotoKeyPress() {
        photoKeyEpoch++
        pendingPhotoKeyPress = true
        val emitted = _photoKeyRequests.tryEmit(Unit)
        Log.i(TAG, "requestPhotoKeyPress emitted=$emitted")
    }

    fun hasPendingPhotoKeyPress(): Boolean = pendingPhotoKeyPress

    fun consumePhotoKeyPressEvent(): Boolean {
        if (photoKeyEpoch == lastConsumedPhotoKeyEpoch) {
            return false
        }
        lastConsumedPhotoKeyEpoch = photoKeyEpoch
        pendingPhotoKeyPress = false
        return true
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
            "emitPhotoResult file=${result.file?.name} captureOnly=${result.captureOnly} " +
                "success=${result.uploadResult.isSuccess} emitted=$emitted",
        )
    }

    fun consumeAutoConnect(): Boolean {
        if (!pendingAutoConnect) return false
        pendingAutoConnect = false
        Log.d(TAG, "consumeAutoConnect=true")
        return true
    }
}
