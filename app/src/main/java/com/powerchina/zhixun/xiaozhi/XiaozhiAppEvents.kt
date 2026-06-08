package com.powerchina.zhixun.xiaozhi

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.powerchina.zhixun.dashcam.SharedCameraCapture
import com.powerchina.zhixun.physicalkey.PhotoKeyLog

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
    /** MCP 拍照完成、识别尚未返回时先发预览 */
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

    private val _photoResults = MutableSharedFlow<PhotoResult>(
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

    /** MCP 拍照/等待服务器回复期间，禁止恢复后台唤醒监听 */
    @Volatile
    var photoSessionActive: Boolean = false
        private set

    @Volatile
    private var photoSessionGeneration: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val PHOTO_SESSION_TIMEOUT_MS = 90_000L

    private val photoSessionTimeoutRunnable = Runnable {
        if (photoSessionActive) {
            Log.w(PhotoKeyLog.TAG, "photoSession 超时 ${PHOTO_SESSION_TIMEOUT_MS}ms，强制结束")
            XiaozhiMcpHandler.abortStuckCapture("photo_session_timeout")
            endPhotoSession(recoverUi = true)
        }
    }

    fun beginPhotoSession(): Long {
        photoSessionGeneration++
        photoSessionActive = true
        mainHandler.removeCallbacks(photoSessionTimeoutRunnable)
        mainHandler.postDelayed(photoSessionTimeoutRunnable, PHOTO_SESSION_TIMEOUT_MS)
        Log.i(PhotoKeyLog.TAG, "beginPhotoSession gen=$photoSessionGeneration")
        return photoSessionGeneration
    }

    fun currentPhotoSessionGeneration(): Long = photoSessionGeneration

    fun endPhotoSession(
        recoverUi: Boolean = false,
        recoverMessage: String? = null,
        sessionGeneration: Long? = null,
    ) {
        if (sessionGeneration != null && sessionGeneration != photoSessionGeneration) {
            Log.d(
                PhotoKeyLog.TAG,
                "endPhotoSession 过期 gen=$sessionGeneration current=$photoSessionGeneration recoverUi=$recoverUi",
            )
            if (recoverUi) {
                val handler = photoSessionRecoverHandler
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    handler?.invoke(recoverMessage)
                } else {
                    mainHandler.post { handler?.invoke(recoverMessage) }
                }
            }
            return
        }
        val wasActive = photoSessionActive
        if (wasActive) {
            mainHandler.removeCallbacks(photoSessionTimeoutRunnable)
            photoSessionActive = false
            SharedCameraCapture.forceReset()
        }
        Log.i(
            PhotoKeyLog.TAG,
            "endPhotoSession recoverUi=$recoverUi wasActive=$wasActive gen=$photoSessionGeneration",
        )
        if (wasActive && !recoverUi) {
            val successHandler = photoRoundSuccessHandler
            if (Looper.myLooper() == Looper.getMainLooper()) {
                successHandler?.invoke()
            } else {
                mainHandler.post { successHandler?.invoke() }
            }
        }
        if (recoverUi) {
            val handler = photoSessionRecoverHandler
            Log.i(
                PhotoKeyLog.TAG,
                "触发 UI 恢复 handler=${handler != null} msg=$recoverMessage",
            )
            if (Looper.myLooper() == Looper.getMainLooper()) {
                handler?.invoke(recoverMessage)
            } else {
                mainHandler.post { handler?.invoke(recoverMessage) }
            }
        }
    }

    /** 拍照失败/超时后由 [ConversationViewModel] 恢复 UI 状态；参数为可选错误信息 */
    @Volatile
    var photoSessionRecoverHandler: ((String?) -> Unit)? = null

    /** MCP 拍照成功（recoverUi=false）后，等待 TTS 播完再恢复拍照前状态 */
    @Volatile
    var photoRoundSuccessHandler: (() -> Unit)? = null

    fun isPhotoSessionActive(): Boolean = photoSessionActive

    /**
     * 用户语音打断拍照：仅清除会话与相机占用，不触发成功/失败恢复回调。
     * 递增 generation，使仍在途的 MCP finally 忽略过期会话。
     */
    fun abortPhotoSession(reason: String) {
        if (!photoSessionActive && photoSessionGeneration == 0L) {
            Log.d(PhotoKeyLog.TAG, "abortPhotoSession 无活动会话 reason=$reason")
            return
        }
        mainHandler.removeCallbacks(photoSessionTimeoutRunnable)
        val wasActive = photoSessionActive
        photoSessionActive = false
        photoSessionGeneration++
        SharedCameraCapture.forceReset()
        Log.i(
            PhotoKeyLog.TAG,
            "abortPhotoSession reason=$reason wasActive=$wasActive gen=$photoSessionGeneration",
        )
    }

    /** 由 [ConversationViewModel] 注册：仅待机/聆听允许拍照键 */
    @Volatile
    var photoKeyGate: () -> Boolean = { true }

    /** 对话页 Compose 是否在前台 */
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

    /** 物理拍照键防抖，避免广播 + KeyEvent 重复触发两次 MCP 拍照 */
    private const val PHOTO_KEY_DEBOUNCE_MS = 800L

    @Volatile
    private var lastPhotoKeyRequestAtMs = 0L

    fun requestPhotoKeyPress() {
        val now = System.currentTimeMillis()
        if (photoSessionActive) {
            Log.d(PhotoKeyLog.TAG, "requestPhotoKeyPress 忽略：拍照会话进行中")
            return
        }
        if (!photoKeyGate()) {
            Log.d(PhotoKeyLog.TAG, "requestPhotoKeyPress 忽略：仅待机/聆听可拍照")
            return
        }
        if (now - lastPhotoKeyRequestAtMs < PHOTO_KEY_DEBOUNCE_MS) {
            Log.d(PhotoKeyLog.TAG, "requestPhotoKeyPress 忽略：防抖 ${now - lastPhotoKeyRequestAtMs}ms")
            return
        }
        lastPhotoKeyRequestAtMs = now
        photoKeyEpoch++
        pendingPhotoKeyPress = true
        val emitted = _photoKeyRequests.tryEmit(Unit)
        Log.i(PhotoKeyLog.TAG, "requestPhotoKeyPress 已投递 emitted=$emitted epoch=$photoKeyEpoch")
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

    fun emitPhotoResult(result: PhotoResult) {
        val deliver = {
            val emitted = _photoResults.tryEmit(result)
            Log.i(
                PhotoKeyLog.TAG,
                "emitPhotoResult file=${result.file?.name} captureOnly=${result.captureOnly} " +
                    "success=${result.uploadResult.isSuccess} emitted=$emitted",
            )
            emitted
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!deliver()) {
                mainHandler.post { deliver() }
            }
        } else {
            mainHandler.post { deliver() }
        }
    }

    fun consumeAutoConnect(): Boolean {
        if (!pendingAutoConnect) return false
        pendingAutoConnect = false
        Log.d(TAG, "consumeAutoConnect=true")
        return true
    }
}
