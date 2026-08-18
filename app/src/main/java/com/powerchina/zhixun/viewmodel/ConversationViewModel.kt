package com.powerchina.zhixun.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.powerchina.zhixun.audio.AudioEvent
import com.powerchina.zhixun.audio.EnhancedAudioManager
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.data.Message
import com.powerchina.zhixun.data.MessageRole
import com.powerchina.zhixun.data.XiaozhiConfig
import com.powerchina.zhixun.dashcam.QuickPhotoCapture
import com.powerchina.zhixun.dashcam.RecordingFrameTts
import com.powerchina.zhixun.dashcam.SharedCameraCapture
import com.powerchina.zhixun.network.MqttUdpEvent
import com.powerchina.zhixun.physicalkey.PhotoKeyLog
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents
import com.powerchina.zhixun.xiaozhi.PhotoResult
import com.powerchina.zhixun.xiaozhi.XiaozhiMcpHandler
import com.powerchina.zhixun.xiaozhi.XiaozhiSessionManager
import com.powerchina.zhixun.xiaozhi.VoiceFlowLog
import com.powerchina.zhixun.xiaozhi.wake.WakePhraseMatcher
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeCoordinator
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 对话状态
 */
enum class ConversationState {
    IDLE,           // 空闲
    CONNECTING,     // 连接中
    LISTENING,      // 聆听中
    PROCESSING,     // 处理中
    SPEAKING        // 说话中
}

/** MQTT 文本/UDP 音频消息统一排队，保证 TTS 控制信令先于音频帧处理 */
private sealed class MqttConversationPayload {
    data class Text(val message: String) : MqttConversationPayload()
    data class Binary(val data: ByteArray) : MqttConversationPayload()
}

/**
 * 对话ViewModel
 */
class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ConversationViewModel"
        /** 说「退下」后过滤服务器回复：adb logcat -s SessionEndReply */
        private const val SESSION_END_TAG = "SessionEndReply"
        /** 全流程诊断：adb logcat -s VoiceFlow */
        private const val SPEAKING_WATCHDOG_MS = 90_000L
        /** 已出声后句间静音超过该值才判定无音频 */
        private const val SPEAKING_NO_AUDIO_MS = 3_000L
        /** 长回答合成首包可能较慢，未出声前放宽等待 */
        private const val SPEAKING_FIRST_AUDIO_MS = 18_000L
        /** 句间/句尾排空：连续空闲这么久才结束回合（对齐 Opus 播放空闲判定） */
        private const val ASSISTANT_REPLY_IDLE_MS = 900L
        /** 已发送「拍照」后等待 MCP take_photo 的最长时间 */
        private const val PHOTO_MCP_WAIT_MS = 25_000L
        /** HTTP 500 等失败后，忽略服务端迟来 TTS，避免卡在说话中 */
        private const val PHOTO_FAILURE_TTS_SUPPRESS_MS = 10_000L
        /** MCP 成功但 TTS 迟迟不来时，仍恢复到拍照前状态 */
        private const val PHOTO_ROUND_TTS_RESET_MS = 45_000L
        /** detect 后忽略服务器迟来问候控制信令，避免误结束；音频可能延迟 10s+ */
        private const val WAKE_GREETING_SUPPRESS_MS = 30_000L
        /** 待机时唤醒连接被空闲关闭后的快速重连宽限期，期间 UI 维持「待机」 */
        private const val STANDBY_RECONNECT_GRACE_MS = 6_000L
        /** 聊天消息列表上限，避免长会话 OOM */
        private const val MAX_MESSAGES = 100
        /** 待机拍照键等待连接/页面就绪的最大重试次数 */
        private const val MAX_PHOTO_KEY_RETRIES = 20
        /** 服务端 listen 会话约 30s 超时，对话聆听中需 stop+start 续期（与 WakeSTT 一致） */
        private const val LISTEN_KEEPALIVE_INTERVAL_MS = 12_000L
        /** 近几秒有上行语音则跳过续期，避免打断正在说的话（偶发识别失败） */
        private const val LISTEN_KEEPALIVE_UPLINK_GUARD_MS = 2_500L

        private val ASSISTANT_TOOL_MARKER = Regex(
            """%\s*get_weather(?:\{[^}]*\}|[^\u4e00-\u9fff%]*)""",
            RegexOption.IGNORE_CASE,
        )
        private val ASSISTANT_TOOL_JSON = Regex(
            """\{"name"\s*:\s*"get_?weather"\s*,\s*"arguments"\s*:\s*\{[^}]*\}\s*\}""",
            RegexOption.IGNORE_CASE,
        )
        private val ASSISTANT_GENERIC_TOOL = Regex(
            """%\s*[a-z][a-z0-9_]*(?:\{[^}]*\}|[^\u4e00-\u9fff%]*)""",
            RegexOption.IGNORE_CASE,
        )
        private val ASSISTANT_LEADING_JUNK = Regex("""^[.\s%]+""")
        /** 小智 LLM 返回的心情 Emoji，不应显示在文字气泡里 */
        private val ASSISTANT_EMOJI = Regex("""[\p{Extended_Pictographic}\uFE0F\u200D]""")

        /** 服务端拍照/分析失败话术，触发相机+小智恢复初始待机 */
        private val PHOTO_SERVER_ERROR_MARKERS = listOf(
            "拍照分析请求超时",
            "未能完成检测",
            "拍照超时",
            "拍照失败",
            "分析请求超时",
            "拍照出错",
            "相机异常",
            "相机错误",
            "服务器内部错误",
            "服务器繁忙",
            "隐患检测",
            "照片上传失败",
            "照片识别失败",
        )
        private val PHOTO_HTTP_ERROR = Regex("""HTTP [45]\d{2}""", RegexOption.IGNORE_CASE)
        /** 服务端拍照过程中的 interim 话术，不应单独占一条气泡 */
        private val PHOTO_INTERIM_STATUS_ONLY = Regex("""^正在拍照[。.……\s]*$""")
    }

    private val gson = Gson()
    private val sessionManager = XiaozhiSessionManager.getInstance(application)
    private val mqttManager = sessionManager.mqttManager
    private val audioManager = EnhancedAudioManager(application)

    // 状态管理
    private val _state = MutableStateFlow(ConversationState.IDLE)
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** MQTT / 唤醒监听均就绪后，待机 UI 才可显示 */
    private val _isStandbyReady = MutableStateFlow(false)
    val isStandbyReady: StateFlow<Boolean> = _isStandbyReady.asStateFlow()

    /** 断线后等待自动重连（此期间 UI 不显示待机） */
    private val _isAwaitingReconnect = MutableStateFlow(false)
    val isAwaitingReconnect: StateFlow<Boolean> = _isAwaitingReconnect.asStateFlow()

    /** 唤醒问候 TTS 播放中（状态仍为 LISTENING，UI 显示「回复中」） */
    private val _isWakeGreetingPlaying = MutableStateFlow(false)
    val isWakeGreetingPlaying: StateFlow<Boolean> = _isWakeGreetingPlaying.asStateFlow()

    /** 唤醒词已触发、开麦交接进行中（UI 避免显示「待机」） */
    private val _isWakeHandoffActive = MutableStateFlow(false)
    val isWakeHandoffActive: StateFlow<Boolean> = _isWakeHandoffActive.asStateFlow()

    /** 说「退下」收尾后的断开重连窗口：UI 直接显示「待机」，不露出「连接中」 */
    private val _isSessionEndStandby = MutableStateFlow(false)
    val isSessionEndStandby: StateFlow<Boolean> = _isSessionEndStandby.asStateFlow()

    /** 待机时唤醒连接被服务端空闲关闭→快速重连的宽限窗口：UI 维持「待机」，不闪「连接中」 */
    private val _isStandbyReconnecting = MutableStateFlow(false)
    val isStandbyReconnecting: StateFlow<Boolean> = _isStandbyReconnecting.asStateFlow()

    /** 待机黑屏休眠：已主动断连，亮屏后再重连；UI 仍显示「待机」并继续息屏计时 */
    private val _isStandbyScreenSleep = MutableStateFlow(false)
    val isStandbyScreenSleep: StateFlow<Boolean> = _isStandbyScreenSleep.asStateFlow()
    private var standbyReconnectGraceJob: Job? = null

    val isSessionConnecting: StateFlow<Boolean> = sessionManager.isConnecting

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // 激活弹窗状态
    private val _showActivationDialog = MutableStateFlow(false)
    val showActivationDialog: StateFlow<Boolean> = _showActivationDialog.asStateFlow()
    
    private val _activationCode = MutableStateFlow<String?>(null)
    val activationCode: StateFlow<String?> = _activationCode.asStateFlow()

    // 配置管理
    private val configManager = ConfigManager(application)
    private var config = configManager.loadConfig()
    
    // 多轮对话支持
    private var isAutoMode = false
    private var currentUserMessage: String? = null

    // 音频初始化状态
    private var isAudioInitialized = false

    // MQTT 已连接但音频尚未就绪时，延后自动开麦
    private var pendingAutoStart = false

    // 对话页是否在前台（离开聊天页时暂停，返回时恢复）
    private var conversationUiActive = false

    // 离开聊天页前是否在语音对话中（用于返回后恢复聆听）
    private var shouldResumeOnUiReturn = false
    private var resumeManualListening = false

    /** 短指令(如「拍照」)发送后，隐藏紧接着回来的那条 STT 回显，不进聊天框（对齐 esp32 hide_next_stt_message_） */
    private var hideNextSttEcho = false

    // 语音唤醒「你好，智询」后待进入对话
    private var pendingVoiceWake = false

    // 物理录音键（138）待连接后开麦
    private var pendingRecordKeyStart = false
    /** 待机拍照键：连接/页面就绪后补发 detect */
    private var pendingPhotoFromStandby = false
    private var pendingPhotoRetryCount = 0
    private var pendingPhotoRetryJob: Job? = null
    /** 文本消息在后台单线程顺序处理，避免 Gson 解析阻塞主线程 */
    private val textMessageDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val mqttConversationChannel = Channel<MqttConversationPayload>(capacity = Channel.BUFFERED)
    private var pendingRecordKeyRetryJob: Job? = null
    private var pendingRecordKeyRetryCount = 0

    private var pendingWakeRetryJob: Job? = null
    private var pendingWakeRetryCount = 0

    private var speakingWatchdogJob: Job? = null
    /**
     * 普通助手回答进行中（多句 tts start/stop）。
     * 用于：勿过早回聆听、勿丢 sentence_end、勿把续句当成迟来问候 abort。
     */
    private var assistantReplyActive = false
    private var assistantReplyAudioReceived = false
    private var assistantReplyTtsStopSeen = false
    private var assistantReplyDrainJob: Job? = null
    private var photoMcpWaitJob: Job? = null
    private var photoMcpWaitToken = 0
    /** 已发「拍照」、尚未收到 MCP take_photo 本地成片前，忽略上一轮迟来 TTS */
    private var photoAwaitingMcpCapture = false
    /** 下一轮助手回复强制新开气泡（拍照键/STT 被隐藏时无 USER 锚点） */
    private var forceNextAssistantBubble = false
    /** 拍照轮次唯一助手气泡，避免成片前后各出一条重复文案 */
    private var photoRoundAssistantMessageId: String? = null
    /** 按下拍照键前的对话状态，轮次结束后恢复 */
    private var stateBeforePhotoRound: ConversationState? = null
    /** 从待机（IDLE）发起的拍照：结束后必须回待机，禁止误进「聆听中」 */
    private var photoStartedFromStandby = false
    /** MCP 已成功，等 TTS 播完再 restoreStateAfterPhotoRound */
    private var photoRoundAwaitingTtsFinish = false
    /** 上传成功后只允许播放一轮结果 TTS，防止「正在拍照」+结果 / 双结果重复播报 */
    private var photoResultTtsAllowed = false
    private var photoResultTtsStarted = false
    /** 结果 TTS 结束后短时静音，挡住服务端紧跟着的第二轮播报 */
    private var photoPostResultMuteUntilMs = 0L
    private var photoRoundResetJob: Job? = null
    /** 本地结果恢复去重（TTS done / fallback 只恢复一次） */
    private var photoLocalRestoreToken = 0
    private var photoRecoveryJob: Job? = null
    private var photoFailureTtsSuppressUntilMs = 0L
    private var listenHandoffJob: Job? = null
    private var listeningKeepaliveJob: Job? = null
    private var hasLoggedFirstAudioFrame = false
    /** 最近一次上行 Opus 帧时间；续期避让用 */
    private var lastUplinkAudioAtMs: Long = 0L
    /** 本轮自动聆听开始时间；用于区分「刚开麦断线」与「服务端长静音超时」 */
    private var lastAutoListeningStartedAtMs: Long = 0L
    /** 本轮唤醒是否已发过 detect，避免 handoff 再发一次造成偶发双问候/乱序 */
    private var wakeDetectSentThisRound = false
    /**
     * 唤醒问候 TTS 未到：刷新 MQTT 后重新 detect。
     * 避免「只显示你好智询 + 假聆听中」且重连后也不再问候。
     */
    private var pendingWakeGreetingRefresh = false
    private var wakeGreetingRefreshAttempts = 0
    /** 本轮唤醒未收到问候 TTS（含刷新后仍失败），断线重连时优先重新 detect */
    private var wakeGreetingFailedThisRound = false

    /** 语音唤醒 → 对话开麦交接中，忽略服务器 TTS/STT 回显 */
    private var wakeConversationHandoff = false
    /** detect 后短暂忽略服务器问候 TTS/LLM，保持聆听态 */
    private var suppressWakeGreetingUntilMs = 0L
    /** 是否已收到唤醒问候 TTS 音频（用于过滤音频到达前的迟来 stop） */
    private var wakeGreetingAudioReceived = false
    /** 是否已收到唤醒问候 TTS start（handoff 需在其后再 listen/start，避免打断音频） */
    private var wakeGreetingTtsStartSeen = false
    /** 是否已收到唤醒问候 TTS stop */
    private var wakeGreetingTtsStopSeen = false
    /** 首轮问候已结束（后续 STT/TTS 按正常对话处理） */
    private var wakeGreetingPhaseComplete = false
    /** 问候阶段已发送 listen/start（开麦时无需重复发送） */
    private var wakeGreetingListenActive = false
    /** 开麦后短暂屏蔽噪声 STT 触发的多余 TTS（语音+文案） */
    private var suppressPostWakeSpuriousTtsUntilMs = 0L

    /** 用户说了「退下」等：等待结束语播完 → 断开重连 → 待机唤醒 */
    private var pendingSessionEnd = false
    private var sessionEndFallbackJob: Job? = null
    private var sessionEndStandbyJob: Job? = null
    private var sessionEndReconnectPending = false
    private var sessionEndAudioReceived = false
    private var sessionEndTtsStopSeen = false
    private var standbyReadyPollJob: Job? = null

    init {
        startEventListening()
        startMqttConversationProcessor()
        viewModelScope.launch {
            sessionManager.isConnected.collect { connected ->
                _isConnected.value = connected
                if (connected) {
                    if (_state.value == ConversationState.CONNECTING) {
                        _state.value = ConversationState.IDLE
                    }
                    tryHandlePendingVoiceWake()
                    tryHandlePendingRecordKeyStart()
                    tryHandlePendingPhotoKey()
                    if (pendingWakeGreetingRefresh) {
                        tryCompleteWakeGreetingRefresh()
                    } else if (pendingAutoStart && isAutoMode) {
                        tryStartAutoConversationIfNeeded()
                    }
                } else {
                    _isAwaitingReconnect.value = mqttManager.isAutoReconnectEnabled() &&
                        _state.value == ConversationState.CONNECTING
                }
                updateStandbyReady()
                if (connected) {
                    scheduleStandbyReadyPoll()
                }
            }
        }
        viewModelScope.launch {
            sessionManager.isConnecting.collect { connecting ->
                if (connecting) _state.value = ConversationState.CONNECTING
                updateStandbyReady()
            }
        }
        viewModelScope.launch {
            sessionManager.lastError.collect { err ->
                if (!err.isNullOrBlank()) _errorMessage.value = err
            }
        }
        viewModelScope.launch {
            sessionManager.activationCode.collect { _activationCode.value = it }
        }
        viewModelScope.launch {
            sessionManager.awaitingActivation.collect { _showActivationDialog.value = it }
        }
        viewModelScope.launch {
            XiaozhiAppEvents.photoResults.collect { result ->
                onPhotoUploadResult(result)
            }
        }
        if (XiaozhiAppEvents.hasPendingVoiceKeyPress()) {
            pendingRecordKeyStart = true
        }
        XiaozhiAppEvents.photoKeyGate = { isPhotoKeyAllowedNow() }
        XiaozhiAppEvents.photoSessionRecoverHandler = { message ->
            recoverAfterPhotoInterrupted("photo_session_recover", message)
        }
        XiaozhiAppEvents.photoRoundSuccessHandler = {
            markPhotoRoundSuccessPendingTts()
        }
    }

    /** 物理拍照键仅待机 / 聆听可用；页面未就绪时延后到 [onPhotoKeyPressed] 再判 */
    private fun isPhotoKeyAllowedNow(): Boolean {
        if (XiaozhiAppEvents.isPhotoSessionActive()) return false
        if (!conversationUiActive) return true
        return _state.value == ConversationState.IDLE ||
            _state.value == ConversationState.LISTENING
    }

    private fun markPendingPhotoFromStandby() {
        pendingPhotoFromStandby = true
        pendingPhotoRetryCount = 0
    }

    private fun cancelPendingPhotoKey(reason: String) {
        if (!pendingPhotoFromStandby && pendingPhotoRetryJob?.isActive != true) return
        pendingPhotoFromStandby = false
        pendingPhotoRetryCount = 0
        pendingPhotoRetryJob?.cancel()
        Log.d(PhotoKeyLog.TAG, "取消待处理拍照: $reason")
    }

    private fun onPhotoUploadResult(result: PhotoResult) {
        if (result.captureOnly) {
            // 成片仅预览；仍屏蔽 TTS，直到 MCP 结果回传后才允许播报一轮
            cancelPhotoMcpWait()
            result.file?.let { showPhotoImage(it.absolutePath) }
            Log.i(PhotoKeyLog.TAG, "本地成片已展示，继续屏蔽中间 TTS 直至上传结果")
            return
        }
        val localText = result.localResultText?.trim().orEmpty()
        if (localText.isNotEmpty()) {
            completePhotoRoundLocally(localText, result.file?.absolutePath)
            return
        }
        // MCP 上传/失败由 XiaozhiMcpHandler finally 统一 endPhotoSession
    }

    /**
     * 服务端未下发 tools/call：检测结果已拿到，本地出字 + 系统 TTS，并恢复拍照前状态。
     * （否则会一直卡在「思考中」等不会到来的小智语音）
     */
    private fun completePhotoRoundLocally(text: String, imagePath: String?) {
        cancelPhotoMcpWait()
        cancelPhotoRecoveryJobs()
        clearPhotoRoundPendingReset()
        clearPhotoAwaitingMcpCapture()
        clearPhotoResultTtsGate()
        photoPostResultMuteUntilMs = 0L
        forceNextAssistantBubble = false
        hideNextSttEcho = false
        // 成片已在 captureOnly 时展示过，这里只补文字，避免出现两张图
        if (imagePath != null && _messages.value.none { it.imagePath == imagePath }) {
            showPhotoImage(imagePath)
        }
        updatePhotoRoundAssistantText(text, preferReplace = true)
        val resumeListening = !photoStartedFromStandby &&
            stateBeforePhotoRound == ConversationState.LISTENING
        val restoreToken = ++photoLocalRestoreToken
        fun restoreOnce(reason: String) {
            if (restoreToken != photoLocalRestoreToken) return
            // 已恢复过（stateBefore 已清空）则跳过
            if (stateBeforePhotoRound == null &&
                !photoStartedFromStandby &&
                _state.value != ConversationState.PROCESSING
            ) {
                return
            }
            Log.i(PhotoKeyLog.TAG, "本地拍照结果恢复 UI reason=$reason resumeListen=$resumeListening")
            restoreStateAfterPhotoRound("photo_local_result", requireTtsPending = false)
        }
        RecordingFrameTts.resetDedupe()
        Log.i(PhotoKeyLog.TAG, "无 MCP，本地展示并播报: $text resumeListen=$resumeListening")
        if (resumeListening) {
            // 聆听中：必须等系统 TTS 播完再开麦，否则会把播报录进去再次触发对话
            RecordingFrameTts.speak(getApplication(), text) {
                viewModelScope.launch { restoreOnce("tts_done") }
            }
            viewModelScope.launch {
                val fallbackMs = (text.length * 280L + 2_000L).coerceIn(3_500L, 15_000L)
                delay(fallbackMs)
                restoreOnce("tts_fallback")
            }
        } else {
            RecordingFrameTts.speak(getApplication(), text)
            viewModelScope.launch {
                delay(200)
                restoreOnce("standby")
            }
        }
    }

    private fun markPhotoAwaitingMcpCapture() {
        photoAwaitingMcpCapture = true
        photoResultTtsAllowed = false
        photoResultTtsStarted = false
        photoPostResultMuteUntilMs = 0L
    }

    private fun clearPhotoAwaitingMcpCapture() {
        photoAwaitingMcpCapture = false
    }

    private fun clearPhotoResultTtsGate() {
        photoResultTtsAllowed = false
        photoResultTtsStarted = false
    }

    /**
     * 拍照轮次 TTS 门闩：
     * - 上传完成前：丢弃所有下行语音（含「正在拍照」）
     * - 上传完成后：只放行一轮结果播报
     * - 结果播完后短时静音：挡住服务端第二轮重复 TTS
     */
    private fun shouldSuppressPhotoTtsPlayback(reason: String): Boolean {
        if (System.currentTimeMillis() < photoPostResultMuteUntilMs) {
            Log.i(PhotoKeyLog.TAG, "抑制拍照后重复 TTS ($reason)")
            return true
        }
        if (photoResultTtsAllowed) {
            if (photoResultTtsStarted && reason == "tts_start") {
                Log.i(PhotoKeyLog.TAG, "抑制重复结果 TTS ($reason)")
                return true
            }
            return false
        }
        val inPhoto =
            XiaozhiAppEvents.isPhotoSessionActive() ||
                photoAwaitingMcpCapture ||
                photoStartedFromStandby ||
                stateBeforePhotoRound != null ||
                photoRoundAwaitingTtsFinish
        if (inPhoto) {
            Log.i(PhotoKeyLog.TAG, "抑制拍照中间 TTS ($reason) awaiting=$photoAwaitingMcpCapture")
            return true
        }
        return false
    }

    /**
     * 拍照等待期间用户开麦/唤醒：立即结束拍照轮次，避免卡在「思考中」且 STT 被屏蔽。
     */
    private fun abortPhotoSessionForVoiceInput(reason: String) {
        val hadPhotoRound = XiaozhiAppEvents.isPhotoSessionActive() ||
            photoAwaitingMcpCapture ||
            stateBeforePhotoRound != null ||
            photoRoundAwaitingTtsFinish
        if (!hadPhotoRound) return

        Log.i(PhotoKeyLog.TAG, "语音打断拍照轮次 reason=$reason state=${_state.value}")
        photoLocalRestoreToken++
        XiaozhiMcpHandler.abortStuckCapture("voice_$reason")
        cancelPhotoMcpWait()
        cancelPhotoRecoveryJobs()
        clearPhotoRoundPendingReset()
        clearPhotoAwaitingMcpCapture()
        stateBeforePhotoRound = null
        photoStartedFromStandby = false
        forceNextAssistantBubble = false
        hideNextSttEcho = false
        clearPhotoFailureTtsSuppress()
        cancelPendingPhotoKey("voice_$reason")
        XiaozhiAppEvents.abortPhotoSession(reason)
        XiaozhiMcpHandler.cancelTakePhotoFallback()
        SharedCameraCapture.forceReset()
    }

    /** 拍照上传完成前 / 结果已播过一轮：忽略服务端 TTS 控制 */
    private fun shouldIgnoreStalePhotoTtsControl(): Boolean =
        shouldSuppressPhotoTtsPlayback("tts_control")

    private fun schedulePhotoMcpWait(rememberState: ConversationState? = null) {
        cancelPhotoRecoveryJobs()
        cancelListenHandoff("photo_mcp_wait")
        clearPhotoFailureTtsSuppress()
        markPhotoAwaitingMcpCapture()
        photoRoundAssistantMessageId = null
        stateBeforePhotoRound = rememberState ?: _state.value
        Log.i(
            PhotoKeyLog.TAG,
            "记住拍照前状态 state=$stateBeforePhotoRound fromStandby=$photoStartedFromStandby",
        )
        val token = ++photoMcpWaitToken
        photoMcpWaitJob?.cancel()
        photoMcpWaitJob = viewModelScope.launch {
            delay(PHOTO_MCP_WAIT_MS)
            if (token != photoMcpWaitToken) return@launch
            Log.w(PhotoKeyLog.TAG, "等待 MCP 拍照超时 ${PHOTO_MCP_WAIT_MS}ms，恢复聆听")
            XiaozhiMcpHandler.abortStuckCapture("photo_mcp_wait")
            val timeoutMessage = "等待拍照响应超时，请重试"
            if (XiaozhiAppEvents.isPhotoSessionActive()) {
                XiaozhiAppEvents.endPhotoSession(
                    recoverUi = true,
                    recoverMessage = timeoutMessage,
                )
            } else {
                recoverAfterPhotoInterrupted("photo_mcp_timeout", timeoutMessage)
            }
        }
    }

    private fun cancelPhotoMcpWait() {
        photoMcpWaitToken++
        photoMcpWaitJob?.cancel()
        photoMcpWaitJob = null
    }

    private fun isPhotoFailureTtsSuppressActive(): Boolean =
        System.currentTimeMillis() < photoFailureTtsSuppressUntilMs

    private fun armPhotoFailureTtsSuppress(reason: String) {
        photoFailureTtsSuppressUntilMs =
            System.currentTimeMillis() + PHOTO_FAILURE_TTS_SUPPRESS_MS
        Log.i(
            PhotoKeyLog.TAG,
            "屏蔽失败后迟来 TTS ${PHOTO_FAILURE_TTS_SUPPRESS_MS}ms reason=$reason",
        )
    }

    private fun clearPhotoFailureTtsSuppress() {
        photoFailureTtsSuppressUntilMs = 0L
    }

    private fun handlePhotoFailureTtsSuppress(event: String): Boolean {
        if (!isPhotoFailureTtsSuppressActive()) return false
        Log.i(
            PhotoKeyLog.TAG,
            "拍照失败恢复期，忽略 TTS $event state=${_state.value}",
        )
        mqttManager.sendAbort("photo_failure_$event")
        audioManager.stopPlaying()
        cancelSpeakingWatchdog()
        if (_state.value != ConversationState.IDLE) {
            transitionState(ConversationState.IDLE, "photo_failure_$event")
        }
        prepareStandbyWakeListening()
        updateStandbyReady()
        return true
    }

    /** 上传失败/超时/服务端报错：相机释放 + 小智恢复初始待机 */
    private fun recoverAfterPhotoInterrupted(reason: String, userMessage: String? = null) {
        userMessage?.let { _errorMessage.value = sanitizePhotoError(it) }
        resetPhotoFlowToInitialStandby("recover_$reason", userMessage)
    }

    private fun isInPhotoFlowContext(): Boolean =
        photoRoundAwaitingTtsFinish ||
            XiaozhiAppEvents.isPhotoSessionActive() ||
            photoAwaitingMcpCapture ||
            stateBeforePhotoRound != null ||
            hideNextSttEcho ||
            (_state.value == ConversationState.PROCESSING && isAutoMode) ||
            (_state.value == ConversationState.SPEAKING && isAutoMode && hideNextSttEcho)

    private fun isInPhotoErrorRecoveryContext(): Boolean =
        isInPhotoFlowContext() || isPhotoFailureTtsSuppressActive()

    private fun isPhotoServerErrorText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (PHOTO_HTTP_ERROR.containsMatchIn(trimmed)) return true
        if (trimmed.contains("\"detail\"") && trimmed.contains("服务器内部错误")) return true
        return PHOTO_SERVER_ERROR_MARKERS.any { trimmed.contains(it) }
    }

    /** 服务端拍照相关错误：立即释放相机并回到 IDLE 待机（唤醒监听） */
    private fun handlePhotoServerErrorFromTts(text: String, event: String): Boolean {
        if (!isPhotoServerErrorText(text)) return false
        if (!isInPhotoErrorRecoveryContext()) return false
        Log.w(PhotoKeyLog.TAG, "服务端拍照错误($event): $text → 恢复初始待机")
        resetPhotoFlowToInitialStandby("server_tts_$event", text)
        return true
    }

    private fun resetPhotoFlowToInitialStandby(reason: String, serverMessage: String? = null) {
        photoLocalRestoreToken++
        XiaozhiMcpHandler.abortStuckCapture(reason)
        XiaozhiMcpHandler.cancelTakePhotoFallback()
        cancelPhotoMcpWait()
        cancelPhotoRecoveryJobs()
        clearPhotoRoundState()
        clearPhotoAwaitingMcpCapture()
        forceNextAssistantBubble = false
        hideNextSttEcho = false
        cancelPendingPhotoKey(reason)
        cancelSpeakingWatchdog()
        cancelListenHandoff("photo_reset:$reason")
        if (XiaozhiAppEvents.isPhotoSessionActive()) {
            XiaozhiAppEvents.abortPhotoSession(reason)
        }
        SharedCameraCapture.forceReset()
        audioManager.stopPlaying()
        audioManager.stopRecording()
        isAutoMode = false
        pendingAutoStart = false
        shouldResumeOnUiReturn = false
        stateBeforePhotoRound = null
        photoStartedFromStandby = false
        if (_isConnected.value) {
            mqttManager.sendAbort("photo_reset_$reason")
            mqttManager.sendStopListening()
        }
        transitionState(ConversationState.IDLE, "photo_reset_initial")
        prepareStandbyWakeListening()
        updateStandbyReady()
        armPhotoFailureTtsSuppress(reason)
        VoiceFlowLog.snapshot("photo.reset.initial", "reason=$reason msg=$serverMessage")
        Log.i(
            PhotoKeyLog.TAG,
            "相机+小智已恢复初始待机 reason=$reason serverMsg=$serverMessage",
        )
    }

    private fun cancelPhotoRecoveryJobs() {
        photoRecoveryJob?.cancel()
        photoRecoveryJob = null
    }

    private fun schedulePhotoListenResume(trigger: String) {
        cancelPhotoRecoveryJobs()
        photoRecoveryJob = viewModelScope.launch {
            delay(150)
            if (XiaozhiAppEvents.isPhotoSessionActive()) {
                Log.d(PhotoKeyLog.TAG, "拍照会话进行中，跳过延迟恢复开麦 trigger=$trigger")
                return@launch
            }
            if (!conversationUiActive || !_isConnected.value) {
                pendingAutoStart = true
                return@launch
            }
            resumeListeningDirectly(trigger)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun resumeListeningDirectly(reason: String) {
        if (XiaozhiAppEvents.isPhotoSessionActive()) {
            Log.d(PhotoKeyLog.TAG, "拍照会话进行中，跳过恢复开麦 reason=$reason")
            return
        }
        if (!conversationUiActive || !_isConnected.value) {
            pendingAutoStart = true
            Log.w(PhotoKeyLog.TAG, "拍照失败后暂无法开麦，待页面/连接就绪")
            return
        }
        XiaozhiWakeForegroundService.claimMicrophoneForConversationAwait(getApplication())
        if (!ensureRecordingReady()) {
            transitionState(ConversationState.IDLE, reason)
            pendingAutoStart = true
            return
        }
        audioManager.stopRecording()
        if (!audioManager.startRecording()) {
            transitionState(ConversationState.IDLE, reason)
            pendingAutoStart = true
            return
        }
        mqttManager.sendStartListening("auto")
        transitionState(ConversationState.LISTENING, "photo_fail_resume")
        scheduleListeningHealthCheck()
        updateStandbyReady()
        Log.i(PhotoKeyLog.TAG, "拍照失败后已恢复聆听（直接开麦）")
    }

    private fun markPhotoRoundSuccessPendingTts() {
        photoAwaitingMcpCapture = false
        photoRoundAwaitingTtsFinish = true
        photoResultTtsAllowed = true
        photoResultTtsStarted = false
        // 清掉「正在拍照」等中间语音，只等结果 TTS
        audioManager.stopPlaying()
        schedulePhotoRoundResetFallback()
        Log.i(PhotoKeyLog.TAG, "拍照上传成功，仅放行一轮结果 TTS")
    }

    private fun clearPhotoRoundPendingReset() {
        photoRoundAwaitingTtsFinish = false
        photoRoundResetJob?.cancel()
        photoRoundResetJob = null
        clearPhotoResultTtsGate()
    }

    private fun clearPhotoRoundState() {
        clearPhotoRoundPendingReset()
        stateBeforePhotoRound = null
        photoRoundAssistantMessageId = null
    }

    private fun schedulePhotoRoundResetFallback() {
        photoRoundResetJob?.cancel()
        photoRoundResetJob = viewModelScope.launch {
            delay(PHOTO_ROUND_TTS_RESET_MS)
            if (!photoRoundAwaitingTtsFinish) return@launch
            Log.w(PhotoKeyLog.TAG, "拍照 TTS 超时 ${PHOTO_ROUND_TTS_RESET_MS}ms，强制恢复拍照前状态")
            restoreStateAfterPhotoRound("photo_round_tts_timeout", requireTtsPending = true)
        }
    }

    /** 一轮拍照结束（成功 TTS 播完 / HTTP500 / MCP 超时）后，恢复到按下拍照键之前的状态 */
    private fun restoreStateAfterPhotoRound(
        trigger: String,
        requireTtsPending: Boolean = false,
    ) {
        if (requireTtsPending && !photoRoundAwaitingTtsFinish) return
        // 待机发起的拍照一律回 IDLE 待机，避免中间 TTS 把状态带进 LISTENING
        val restoreTarget = if (photoStartedFromStandby) {
            ConversationState.IDLE
        } else {
            stateBeforePhotoRound ?: ConversationState.IDLE
        }
        val fromStandby = photoStartedFromStandby
        photoStartedFromStandby = false
        clearPhotoRoundState()
        clearPhotoAwaitingMcpCapture()
        forceNextAssistantBubble = false
        hideNextSttEcho = false
        cancelPhotoMcpWait()
        XiaozhiMcpHandler.cancelTakePhotoFallback()
        cancelSpeakingWatchdog()
        cancelPhotoRecoveryJobs()
        clearPhotoFailureTtsSuppress()
        SharedCameraCapture.forceReset()

        audioManager.stopPlaying()
        audioManager.stopRecording()
        cancelListenHandoff("photo_round_reset:$trigger")

        if (_isConnected.value) {
            mqttManager.sendStopListening()
        }

        Log.i(
            PhotoKeyLog.TAG,
            "拍照轮次结束 trigger=$trigger fromStandby=$fromStandby → 恢复 state=$restoreTarget",
        )

        when (restoreTarget) {
            ConversationState.LISTENING -> {
                isAutoMode = true
                pendingAutoStart = false
                transitionState(ConversationState.IDLE, "photo_round_reset_prep")
                schedulePhotoListenResume("photo_round_reset")
            }
            ConversationState.IDLE -> {
                isAutoMode = false
                pendingAutoStart = false
                transitionState(ConversationState.IDLE, "photo_round_reset_idle")
                prepareStandbyWakeListening()
            }
            else -> {
                isAutoMode = false
                pendingAutoStart = false
                transitionState(ConversationState.IDLE, "photo_round_reset")
                prepareStandbyWakeListening()
            }
        }
    }

    private fun showPhotoImage(imagePath: String) {
        val file = java.io.File(imagePath)
        if (!file.exists()) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(80)
                if (file.exists()) {
                    showPhotoImage(imagePath)
                } else {
                    Log.w(TAG, "照片文件不存在: $imagePath")
                }
            }
            return
        }
        // 同一路径只展示一张，避免 captureOnly + 本地结果各插一次
        if (_messages.value.any { it.imagePath == imagePath }) {
            Log.d(PhotoKeyLog.TAG, "照片已在消息列表中，跳过重复展示")
            return
        }
        removeTrailingPhotoInterimAssistantBubble()
        val photoMsg = Message(
            role = MessageRole.USER,
            content = "",
            imagePath = imagePath,
        )
        val messages = _messages.value.toMutableList()
        val anchorId = photoRoundAssistantMessageId
        val anchorIdx = if (anchorId != null) {
            messages.indexOfFirst { it.id == anchorId }
        } else {
            -1
        }
        if (anchorIdx >= 0) {
            messages.add(anchorIdx, photoMsg)
        } else {
            messages.add(photoMsg)
        }
        _messages.value = messages
        Log.i(PhotoKeyLog.TAG, "展示照片 ${file.name}")
    }

    private fun sanitizePhotoError(message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isBlank()) return "照片上传失败"
        if (raw.contains("服务器内部错误") || PHOTO_HTTP_ERROR.containsMatchIn(raw)) {
            return "服务器繁忙，请稍后重试"
        }
        if (raw.startsWith("{") && raw.contains("\"success\"")) return "照片识别失败，请重试"
        return raw
    }

    private fun isLikelyVisionJsonEcho(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("{") &&
            (trimmed.contains("\"success\"") || trimmed.contains("\"filename\""))
    }

    private fun isPhotoMcpToolLeak(text: String): Boolean =
        XiaozhiMcpHandler.containsTakePhotoToolSignal(text)

    /** 服务端经 STT/TTS 确认拍照意图后，触发本地拍照；保持 TTS 屏蔽直到上传成功 */
    private fun onPhotoServerSignal(text: String, source: String): Boolean {
        if (!XiaozhiAppEvents.isPhotoSessionActive()) return false
        val trimmed = text.trim()
        val isTakePhotoStt = trimmed == "拍照"
        val isTakePhotoTool = isPhotoMcpToolLeak(text)
        if (!isTakePhotoStt && !isTakePhotoTool) return false

        // 不在此处解除 TTS 屏蔽，否则「正在拍照」会先播一遍，结果再播一遍
        cancelPhotoMcpWait()
        Log.i(
            PhotoKeyLog.TAG,
            "拍照服务端信号($source)：安排 fallback，保持屏蔽中间 TTS " +
                "(stt=$isTakePhotoStt tool=$isTakePhotoTool)",
        )
        XiaozhiMcpHandler.scheduleTakePhotoFallback(source)
        return true
    }

    /** 过滤 LLM/TTS 流里泄漏的工具调用标记（如 % get_weather..） */
    private fun sanitizeAssistantText(text: String): String {
        var result = text
        result = ASSISTANT_EMOJI.replace(result, "")
        result = ASSISTANT_TOOL_MARKER.replace(result, "")
        result = ASSISTANT_TOOL_JSON.replace(result, "")
        if ('%' in result) {
            result = ASSISTANT_GENERIC_TOOL.replace(result, "")
        }
        result = ASSISTANT_LEADING_JUNK.replace(result, "")
        return result.replace(Regex("""[ \t]{2,}"""), " ").trim()
    }

    private fun isPhotoInterimStatusText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        return PHOTO_INTERIM_STATUS_ONLY.matches(trimmed) || trimmed == "拍照中"
    }

    /** 成片前服务端可能先播「正在拍照…」，插入照片后不再单独展示这条 interim 气泡 */
    private fun removeTrailingPhotoInterimAssistantBubble() {
        val currentMessages = _messages.value.toMutableList()
        val last = currentMessages.lastOrNull() ?: return
        if (last.role != MessageRole.ASSISTANT || !isPhotoInterimStatusText(last.content)) return
        if (photoRoundAssistantMessageId == last.id) {
            photoRoundAssistantMessageId = null
        }
        currentMessages.removeAt(currentMessages.lastIndex)
        _messages.value = currentMessages
        Log.d(PhotoKeyLog.TAG, "移除拍照 interim 助手气泡: ${last.content}")
    }

    private fun shouldUsePhotoRoundAssistantBubble(): Boolean =
        XiaozhiAppEvents.isPhotoSessionActive() || photoRoundAwaitingTtsFinish

    private fun mergePhotoAssistantContent(
        current: String,
        incoming: String,
        preferReplace: Boolean,
    ): String {
        val cur = sanitizeAssistantText(current)
        val inc = sanitizeAssistantText(incoming)
        if (inc.isBlank()) return cur
        if (cur.isBlank()) return inc
        if (cur == inc) return cur
        if (inc.contains(cur)) return inc
        if (cur.contains(inc)) return cur
        if (preferReplace && inc.length >= cur.length) return inc
        return sanitizeAssistantText(cur + incoming)
    }

    /** 拍照轮次内所有助手文案写入同一条气泡 */
    private fun updatePhotoRoundAssistantText(incoming: String, preferReplace: Boolean) {
        val cleaned = sanitizeAssistantText(incoming)
        if (cleaned.isBlank()) return
        if (isPhotoInterimStatusText(cleaned)) return

        val messages = _messages.value.toMutableList()
        val anchorId = photoRoundAssistantMessageId
        val anchorIdx = if (anchorId != null) {
            messages.indexOfFirst { it.id == anchorId }
        } else {
            -1
        }

        if (anchorIdx >= 0) {
            val anchor = messages[anchorIdx]
            val merged = mergePhotoAssistantContent(anchor.content, cleaned, preferReplace)
            if (merged == anchor.content) return
            messages[anchorIdx] = anchor.copy(content = merged)
            _messages.value = messages
            Log.d(PhotoKeyLog.TAG, "拍照轮次更新助手气泡: $merged")
            return
        }

        val newMsg = Message(role = MessageRole.ASSISTANT, content = cleaned)
        photoRoundAssistantMessageId = newMsg.id
        messages.add(newMsg)
        _messages.value = messages
        Log.d(PhotoKeyLog.TAG, "拍照轮次创建助手气泡: $cleaned")
    }

    /** 本地已展示完整视觉描述时，避免 TTS/LLM 重复追加同一段文字 */
    private fun shouldApplyServerAssistantText(incoming: String): Boolean {
        val text = incoming.trim()
        if (text.isBlank()) return false
        if (shouldSuppressPostWakeSpuriousTts() && !isWakeGreetingTurn()) {
            return false
        }
        if (isPhotoMcpToolLeak(text)) return false
        if (shouldUsePhotoRoundAssistantBubble()) return !isPhotoInterimStatusText(text)
        if (XiaozhiAppEvents.isPhotoSessionActive() && isPhotoInterimStatusText(text)) {
            return false
        }
        val msgs = _messages.value
        val last = msgs.lastOrNull() ?: return true
        if (forceNextAssistantBubble) return true
        if (last.role == MessageRole.USER && last.imagePath != null) return true
        if (last.role != MessageRole.ASSISTANT) return true
        val current = last.content.trim()
        if (current.isBlank()) return true
        if (current == text) return false
        // 服务端发来更长的累积全文：必须允许更新（否则长回答气泡卡在短前缀）
        if (text.length > current.length &&
            (text.startsWith(current) || text.contains(current))
        ) {
            return true
        }
        if (current.contains(text) || text.contains(current)) {
            return false
        }
        return true
    }

    /**
     * 对话页显示/隐藏：离开进入待机，返回恢复离开前的对话状态。
     */
    fun setConversationUiActive(active: Boolean) {
        if (conversationUiActive == active) return
        conversationUiActive = active
        XiaozhiAppEvents.setConversationScreenVisible(active)
        Log.i(TAG, "UI active=$active state=${_state.value} pendingWake=$pendingVoiceWake")
        if (!active) {
            pauseConversationForUi()
        } else {
            resumeConversationForUi()
            if (pendingVoiceWake || pendingRecordKeyStart || XiaozhiAppEvents.hasPendingVoiceKeyPress()) {
                initializeAudio()
            } else if (_state.value == ConversationState.IDLE && !isAutoMode) {
                prepareStandbyWakeListening()
            }
            tryHandlePendingVoiceWake()
            tryHandlePendingRecordKeyStart()
            tryHandlePendingPhotoKey()
        }
        updateStandbyReady()
        if (active) {
            scheduleStandbyReadyPoll()
        }
    }

    /** 待机可显示：已连接 + 唤醒推流正常 + 无对话/交接占用 + 非重连中 */
    private fun updateStandbyReady() {
        val blockers = standbyReadyBlockers()
        val ready = blockers.isEmpty()
        if (_isStandbyReady.value != ready) {
            _isStandbyReady.value = ready
            Log.d(
                TAG,
                "standbyReady=$ready connected=${_isConnected.value} " +
                    "wake=${XiaozhiWakeForegroundService.isWakeListeningHealthy()}",
            )
            VoiceFlowLog.snapshot(
                "standbyReady=$ready",
                "blockers=${blockers.joinToString(", ").ifBlank { "无" }} | ${flowContext()}",
            )
        }
    }

    private fun standbyReadyBlockers(): List<String> {
        val blockers = mutableListOf<String>()
        if (!conversationUiActive) blockers.add("uiInactive")
        if (sessionManager.isConnecting.value) blockers.add("sessionConnecting")
        if (!_isConnected.value) {
            if (sessionManager.isUserStandbyDisconnected()) {
                if (!XiaozhiWakeForegroundService.isWakeListeningHealthy()) {
                    blockers.add("wakeNotHealthy")
                }
            } else {
                blockers.add("disconnected")
            }
        }
        if (_isAwaitingReconnect.value) blockers.add("awaitingReconnect")
        if (_state.value != ConversationState.IDLE) blockers.add("state=${_state.value}")
        if (pendingVoiceWake) blockers.add("pendingWake")
        if (isAutoMode) blockers.add("autoMode")
        if (pendingSessionEnd) blockers.add("pendingSessionEnd")
        if (XiaozhiWakeCoordinator.isWakeHandoffInProgress()) blockers.add("coordHandoff")
        if (XiaozhiWakeForegroundService.isConversationMicClaimed()) blockers.add("micClaimed")
        if (listenHandoffJob?.isActive == true) blockers.add("listenHandoff")
        if (!XiaozhiWakeForegroundService.isWakeListeningHealthy()) blockers.add("wakeNotHealthy")
        return blockers
    }

    /** 一行看清当前语音链路全部关键标志 */
    private fun flowContext(): String = buildString {
        append("state=${_state.value}")
        append(" conn=${_isConnected.value}")
        append(" auto=$isAutoMode")
        append(" pendingWake=$pendingVoiceWake")
        append(" pendingEnd=$pendingSessionEnd")
        append(" vmHandoff=$wakeConversationHandoff")
        append(" coordHandoff=${XiaozhiWakeCoordinator.isWakeHandoffInProgress()}")
        append(" greetWindow=${isWakeGreetingWindow()}")
        append(" greetPlay=${_isWakeGreetingPlaying.value}")
        val greetLeft = suppressWakeGreetingUntilMs - System.currentTimeMillis()
        if (greetLeft > 0) append(" greetLeft=${greetLeft}ms")
        append(" listenHandoff=${listenHandoffJob?.isActive == true}")
        append(" micClaim=${XiaozhiWakeForegroundService.isConversationMicClaimed()}")
        append(" wakeHealthy=${XiaozhiWakeForegroundService.isWakeListeningHealthy()}")
        append(" wakeActive=${XiaozhiWakeForegroundService.isWakeListeningActive()}")
        append(" rec=${audioManager.isRecording()}")
        append(" play=${audioManager.isPlaying()}")
        append(" standby=${_isStandbyReady.value}")
        append(" reconnect=${_isAwaitingReconnect.value}")
    }

    private fun logFlow(event: String, detail: String = "") {
        VoiceFlowLog.snapshot(event, if (detail.isBlank()) flowContext() else "$detail | ${flowContext()}")
    }

    private fun transitionState(to: ConversationState, reason: String) {
        val from = _state.value
        if (from == to) return
        _state.value = to
        if (to == ConversationState.LISTENING && isAutoMode) {
            lastAutoListeningStartedAtMs = System.currentTimeMillis()
            startListeningKeepalive()
        } else if (from == ConversationState.LISTENING && to != ConversationState.LISTENING) {
            stopListeningKeepalive()
        }
        VoiceFlowLog.transition("state", from.name, to.name, reason, flowContext())
    }

    private fun isActivelyInConversation(): Boolean {
        return _state.value == ConversationState.LISTENING ||
            _state.value == ConversationState.PROCESSING ||
            _state.value == ConversationState.SPEAKING ||
            pendingSessionEnd
    }

    /** 已在开麦聆听时收到的迟来 TTS 文本/控制信令（不含音频）应忽略，避免干扰当前轮次 */
    private fun shouldIgnoreStaleReplyWhileListening(): Boolean {
        if (shouldIgnoreStalePhotoTtsControl()) return true
        if (XiaozhiAppEvents.isPhotoSessionActive()) return false
        // 助手长回答尚未播完/文案未落定时，勿当「迟来回显」丢掉
        if (assistantReplyActive || audioManager.isPlaying() ||
            assistantReplyDrainJob?.isActive == true
        ) {
            return false
        }
        return _state.value == ConversationState.LISTENING &&
            isAutoMode &&
            audioManager.isRecording() &&
            !pendingSessionEnd &&
            !isWakeGreetingTurn()
    }

    /**
     * 是否处于「你好，智询」后的问候轮次（含窗口过期但问候 TTS/文案尚未落地）。
     */
    private fun isWakeGreetingTurn(): Boolean {
        if (wakeGreetingPhaseComplete) return false
        if (isWakeGreetingWindow() || _isWakeGreetingPlaying.value || wakeGreetingAudioReceived) {
            return true
        }
        if (!isAutoMode) return false
        if (_state.value != ConversationState.LISTENING && _state.value != ConversationState.IDLE) {
            return false
        }
        val msgs = _messages.value
        val lastUserIdx = msgs.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIdx < 0) return false
        if (!WakePhraseMatcher.matches(msgs[lastUserIdx].content)) return false
        val afterWake = msgs.subList(lastUserIdx + 1, msgs.size)
        val hasSubstantiveReply = afterWake.any { msg ->
            msg.role == MessageRole.ASSISTANT &&
                msg.content.trim().isNotBlank() &&
                !isLikelyEmotionOnly(msg.content)
        }
        return !hasSubstantiveReply || audioManager.isPlaying()
    }

    private fun isWakeHandoffInProgress(): Boolean {
        return wakeConversationHandoff ||
            pendingVoiceWake ||
            pendingWakeGreetingRefresh ||
            listenHandoffJob?.isActive == true ||
            XiaozhiWakeCoordinator.isWakeHandoffInProgress()
    }

    private fun setWakeGreetingPlaying(playing: Boolean) {
        if (_isWakeGreetingPlaying.value == playing) return
        _isWakeGreetingPlaying.value = playing
        VoiceFlowLog.step("wake.greetingPlay", "playing=$playing")
    }

    private fun updateWakeHandoffUi() {
        val active = pendingVoiceWake ||
            wakeConversationHandoff ||
            pendingWakeGreetingRefresh ||
            listenHandoffJob?.isActive == true
        if (_isWakeHandoffActive.value != active) {
            _isWakeHandoffActive.value = active
            VoiceFlowLog.step("wake.handoffUi", "active=$active")
        }
    }

    private fun isLikelyEmotionOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        return trimmed.none { it in '\u4e00'..'\u9fff' || it.isLetter() }
    }

    private fun ensureDownlinkPlaybackReady(forceReprepare: Boolean = false) {
        if (forceReprepare || !audioManager.isPlaybackPipelineActive()) {
            if (!audioManager.reprepareDownlinkPlayback() && !audioManager.isPlaybackReady()) {
                audioManager.initializePlaybackOnly()
            }
            return
        }
        if (!audioManager.isPlaybackReady()) {
            audioManager.initializePlaybackOnly()
        }
    }

    private fun markWakeGreetingTtsStart() {
        wakeGreetingTtsStartSeen = true
        // TTS 已开始即停静音保活，减少被服务端当成用户说话
        mqttManager.stopWakeGreetingNatKeepalive("tts_start")
        ensureDownlinkPlaybackReady()
        setWakeGreetingPlaying(true)
        if (_state.value != ConversationState.SPEAKING) {
            transitionState(ConversationState.SPEAKING, "wake_greeting_tts")
        }
        Log.i(TAG, "问候 TTS start → SPEAKING（官方流程，停止上行）")
    }

    private fun completeWakeGreetingPhase(reason: String) {
        val alreadyComplete = wakeGreetingPhaseComplete
        wakeGreetingPhaseComplete = true
        wakeGreetingTtsStopSeen = true
        setWakeGreetingPlaying(false)
        if (suppressWakeGreetingUntilMs > 0L) {
            suppressWakeGreetingUntilMs = 0L
            VoiceFlowLog.step("wake.greetingWindow", "complete 时清除 reason=$reason")
        }
        if (!alreadyComplete) {
            VoiceFlowLog.step("wake.greetingPhase", "complete reason=$reason")
            Log.d(TAG, "唤醒问候阶段结束: $reason")
            armPostWakeSpuriousTtsSuppress("greeting_complete:$reason", durationMs = 5_000L)
        }
    }

    private suspend fun awaitWakeGreetingTtsStart(timeoutMs: Long = 2_500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (wakeGreetingTtsStartSeen ||
                _isWakeGreetingPlaying.value ||
                wakeGreetingAudioReceived
            ) {
                Log.d(TAG, "问候 TTS start 已到达，等待播完再开麦")
                return true
            }
            delay(50)
        }
        Log.w(TAG, "等待问候 TTS start 超时(${timeoutMs}ms)，仍尝试开麦")
        return false
    }

    /** 问候 TTS 播完后再开麦；完成条件：已收音频且 stop/播完 / 超时 */
    private suspend fun awaitWakeGreetingTtsEnd(timeoutMs: Long = 20_000L): Boolean {
        if (!wakeGreetingTtsStartSeen) return false
        var playbackIdleSince = 0L
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            XiaozhiWakeCoordinator.refreshHandoffTimeout(getApplication())
            // 尚未收到 Opus：即使服务端已发 stop 也继续等（NAT 打通后音频可能稍晚）
            if (!wakeGreetingAudioReceived) {
                delay(50)
                continue
            }
            if (wakeGreetingPhaseComplete && !audioManager.isPlaying()) {
                Log.d(TAG, "问候阶段已结束且播完，开始开麦")
                return true
            }
            if (wakeGreetingTtsStopSeen && !audioManager.isPlaying()) {
                if (playbackIdleSince == 0L) playbackIdleSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - playbackIdleSince > 400) {
                    completeWakeGreetingPhase("tts_stop_drained")
                    Log.d(TAG, "问候 TTS stop 且播完，开始开麦")
                    return true
                }
            } else if (!audioManager.isPlaying()) {
                if (playbackIdleSince == 0L) playbackIdleSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - playbackIdleSince > 400) {
                    completeWakeGreetingPhase("audio_done")
                    Log.d(TAG, "问候音频播完，开始开麦")
                    return true
                }
            } else {
                playbackIdleSince = 0L
            }
            delay(50)
        }
        Log.w(TAG, "等待问候 TTS 结束超时(${timeoutMs}ms)，强制开麦")
        mqttManager.stopWakeGreetingNatKeepalive("greeting_timeout")
        completeWakeGreetingPhase("timeout")
        return false
    }

    /** 仅真正「聆听中」才上行：非问候播放、非 TTS 播报 */
    private fun shouldSendUplinkAudio(): Boolean {
        if (_state.value != ConversationState.LISTENING) return false
        if (!audioManager.isRecording()) return false
        if (_isWakeGreetingPlaying.value) return false
        if (audioManager.isPlaying()) return false
        return true
    }

    /** 下行 TTS：SPEAKING/PROCESSING 播放；LISTENING 仅在未录音或问候/拍照窗口播放 */
    private fun shouldPlayDownlinkAudio(): Boolean {
        if (shouldIgnoreStalePhotoTtsControl()) {
            return false
        }
        if (XiaozhiAppEvents.isPhotoSessionActive()) {
            return when (_state.value) {
                ConversationState.SPEAKING,
                ConversationState.LISTENING,
                ConversationState.PROCESSING -> true
                else -> false
            }
        }
        if (isWakeGreetingTurn() && _state.value == ConversationState.SPEAKING) {
            return true
        }
        if (_state.value == ConversationState.LISTENING &&
            isAutoMode &&
            audioManager.isRecording() &&
            !isWakeGreetingTurn()
        ) {
            return false
        }
        if (isWakeGreetingTurn()) {
            return _state.value != ConversationState.CONNECTING
        }
        return when (_state.value) {
            ConversationState.SPEAKING,
            ConversationState.LISTENING,
            ConversationState.PROCESSING -> true
            else -> false
        }
    }

    /**
     * 服务端 listen 约 30s 无活动会过期；UI 仍显示「聆听中」但识别失效（偶发）。
     * 与 WakeSTT 一样每 12s stop+start 续期。
     * 注意：勿用 isWakeGreetingTurn() 作门闩——该启发式偶发长期为 true，会整轮都不续期。
     */
    private fun startListeningKeepalive() {
        listeningKeepaliveJob?.cancel()
        listeningKeepaliveJob = viewModelScope.launch {
            var lastRenew = System.currentTimeMillis()
            while (true) {
                delay(1_000)
                if (_state.value != ConversationState.LISTENING ||
                    !isAutoMode ||
                    !audioManager.isRecording() ||
                    !_isConnected.value ||
                    isWakeHandoffInProgress() ||
                    _isWakeGreetingPlaying.value
                ) {
                    continue
                }
                val now = System.currentTimeMillis()
                if (now - lastRenew < LISTEN_KEEPALIVE_INTERVAL_MS) continue
                // 正在说话时续期会截断 STT，表现为偶发「说了没识别」
                if (now - lastUplinkAudioAtMs < LISTEN_KEEPALIVE_UPLINK_GUARD_MS) {
                    continue
                }
                renewListenSession("keepalive")
                lastRenew = System.currentTimeMillis()
            }
        }
    }

    /** 刷新服务端 listen，不改本地录音状态 */
    private fun renewListenSession(reason: String) {
        if (!_isConnected.value) return
        Log.d(TAG, "续期对话 listen ($reason)")
        VoiceFlowLog.step(
            "listen.renew",
            "reason=$reason session=${mqttManager.getSessionId()}",
        )
        mqttManager.sendStopListening()
        mqttManager.sendStartListening("auto")
    }

    private fun stopListeningKeepalive() {
        listeningKeepaliveJob?.cancel()
        listeningKeepaliveJob = null
    }

    private fun scheduleStandbyReadyPoll() {
        if (!conversationUiActive) return
        standbyReadyPollJob?.cancel()
        standbyReadyPollJob = viewModelScope.launch {
            repeat(40) {
                updateStandbyReady()
                if (_isStandbyReady.value) return@launch
                delay(150)
            }
            updateStandbyReady()
        }
    }

    /** 说「退下」后立即释放麦克风，并在结束语播放期间预初始化唤醒采集 */
    private fun beginSessionEndWindDown() {
        logFlow("sessionEnd.windDown.begin", "释放麦克风并预初始化唤醒采集")
        clearWakeGreetingSuppression()
        cancelListenHandoff("session_end_wind_down")
        audioManager.stopRecording()
        audioManager.releaseRecorderOnly()
        isAudioInitialized = false
        XiaozhiWakeForegroundService.prepareWakeAudioCapture(getApplication())
        Log.d(TAG, "结束语等待：已释放麦克风，预初始化唤醒采集")
    }

    /**
     * 助手主动道别（含「退下了/拜拜」等）时进入结束流程，避免假「聆听中」。
     * @return 本次是否新武装了结束态
     */
    private fun armSessionEndFromAssistantFarewell(text: String, source: String): Boolean {
        if (pendingSessionEnd) return false
        if (text.isBlank() || !WakePhraseMatcher.isAssistantFarewellPhrase(text)) return false
        if (isWakeGreetingWindow() || _isWakeGreetingPlaying.value) return false
        Log.i(TAG, "助手结束语($source): $text → 等待播完进待机")
        logFlow("sessionEnd.assistantFarewell", "source=$source text=$text")
        Log.i(SESSION_END_TAG, "助手道别「$text」，等待播完后待机")
        pendingSessionEnd = true
        sessionEndAudioReceived = audioManager.isPlaying()
        sessionEndTtsStopSeen = false
        isAutoMode = false
        pendingAutoStart = false
        beginSessionEndWindDown()
        scheduleSessionEndFallback()
        if (_state.value == ConversationState.PROCESSING) {
            transitionState(ConversationState.SPEAKING, "session_end_assistant_farewell")
        }
        return true
    }

    private fun pauseConversationForUi() {
        // 开麦交接中 state 常为 IDLE：若按普通暂停清理会掐断 handoff，随后唤醒无问候
        if (pendingVoiceWake ||
            pendingWakeGreetingRefresh ||
            wakeConversationHandoff ||
            listenHandoffJob?.isActive == true ||
            XiaozhiWakeCoordinator.isWakeHandoffInProgress() ||
            XiaozhiAppEvents.isPhotoSessionActive()
        ) {
            VoiceFlowLog.decision("ui.pause", "清理", false, "handoff_or_photo")
            Log.d(PhotoKeyLog.TAG, "唤醒交接/拍照会话中，跳过 UI 暂停清理")
            return
        }

        val current = _state.value
        // 退后台时若正在对话（聆听/处理/说话），记住状态，回前台自动恢复聆听
        val wasActiveConversation = isAutoMode &&
            (current == ConversationState.LISTENING ||
                current == ConversationState.PROCESSING ||
                current == ConversationState.SPEAKING)
        resumeManualListening = false

        VoiceFlowLog.snapshot(
            "ui.pause",
            "state=$current auto=$isAutoMode wasActive=$wasActiveConversation | ${flowContext()}",
        )
        cancelListenHandoff("ui_pause")
        cancelSpeakingWatchdog()
        stopListeningKeepalive()
        audioManager.stopRecording()
        audioManager.stopPlaying()
        audioManager.releaseRecorderOnly()
        isAudioInitialized = false
        // 冻结当前对话：复位自动模式、释放麦克风占用，避免回前台卡在「假聆听」（收不了音）
        isAutoMode = false
        pendingAutoStart = false
        wakeConversationHandoff = false
        when (current) {
            ConversationState.LISTENING -> mqttManager.sendStopListening()
            ConversationState.SPEAKING,
            ConversationState.PROCESSING -> mqttManager.sendAbort("ui_pause")
            else -> Unit
        }
        XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
        _state.value = ConversationState.IDLE
        updateWakeHandoffUi()
        shouldResumeOnUiReturn = wasActiveConversation
        if (wasActiveConversation) {
            // 后台冻结：不在后台占用麦克风/偷听，回前台再恢复聆听
            Log.d(TAG, "对话页退后台 → 冻结，回前台恢复聆听")
            pauseWakeListening()
            updateStandbyReady()
        } else {
            Log.d(TAG, "对话页离开 → 待机")
            resumeWakeListeningIfNeeded()
        }
    }

    private fun resumeConversationForUi() {
        if (pendingVoiceWake || XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
            Log.d(TAG, "唤醒交接中，跳过 UI 恢复")
            shouldResumeOnUiReturn = false
            resumeManualListening = false
            return
        }
        val resume = shouldResumeOnUiReturn
        shouldResumeOnUiReturn = false
        resumeManualListening = false
        if (resume) {
            // 退后台前在对话中：回前台自动恢复聆听（等价于自动按一次录音键开麦）
            Log.i(TAG, "对话页返回 → 恢复聆听 connected=${_isConnected.value}")
            VoiceFlowLog.snapshot("ui.resume", "restoreListening | ${flowContext()}")
            isAutoMode = true
            pendingVoiceWake = false
            pendingRecordKeyStart = true
            pendingAutoStart = false
            pendingRecordKeyRetryCount = 0
            prepareForRecordKeySession()
            return
        }
        Log.d(TAG, "对话页返回 connected=${_isConnected.value}（待机仅按键/唤醒可开聊）")
        tryHandlePendingVoiceWake()
        tryHandlePendingRecordKeyStart()
    }

    /**
     * 物理录音键（138）：
     * - 待机/未连接 → 连接并进入聆听
     * - 聆听 → 结束对话，进入待机
     * - 处理/说话 → 打断当前回复，重新进入聆听
     */
    fun onRecordKeyPressed() {
        sessionManager.clearUserStandbyDisconnect()
        _isSessionEndStandby.value = false
        val current = _state.value
        when (current) {
            ConversationState.LISTENING -> {
                XiaozhiAppEvents.acknowledgeVoiceKeyEvent()
                stopConversationFromVoiceKey()
                return
            }
            ConversationState.PROCESSING,
            ConversationState.SPEAKING -> {
                XiaozhiAppEvents.acknowledgeVoiceKeyEvent()
                restartListeningFromVoiceKey()
                return
            }
            else -> Unit
        }
        if (!XiaozhiAppEvents.consumeVoiceKeyPressEvent()) {
            Log.d(TAG, "录音键：重复开麦事件忽略")
            return
        }
        Log.i(TAG, "录音键：连接并开麦 state=$current connected=${_isConnected.value}")
        isAutoMode = true
        pendingVoiceWake = false
        pendingRecordKeyStart = true
        pendingAutoStart = false
        pendingRecordKeyRetryCount = 0
        prepareForRecordKeySession()
        initializeAudio()
        tryHandlePendingRecordKeyStart()
    }

    /**
     * 物理拍照键（142）：
     * - 待机 → 发送「拍照」唤醒词给服务器，由服务端调 MCP take_photo
     * - 聆听 → 发送「拍照」文字给服务器，由服务端调 MCP take_photo
     */
    fun onPhotoKeyPressed() {
        sessionManager.clearUserStandbyDisconnect()
        if (!XiaozhiAppEvents.consumePhotoKeyPressEvent()) {
            Log.d(PhotoKeyLog.TAG, "拍照键：重复事件忽略")
            return
        }
        if (XiaozhiAppEvents.isPhotoSessionActive()) {
            Log.d(PhotoKeyLog.TAG, "拍照键：拍照会话进行中，忽略")
            return
        }
        if (pendingSessionEnd || pendingVoiceWake || isWakeHandoffInProgress()) {
            Log.d(PhotoKeyLog.TAG, "拍照键：交接/结束语中，忽略")
            return
        }
        if (!conversationUiActive) {
            Log.d(PhotoKeyLog.TAG, "拍照键：对话页未就绪，待页面就绪后发送「拍照」")
            markPendingPhotoFromStandby()
            if (!_isConnected.value) connect()
            schedulePhotoKeyRetry()
            return
        }
        if (!isPhotoKeyAllowedNow()) {
            cancelPendingPhotoKey("当前=${_state.value}")
            Log.d(PhotoKeyLog.TAG, "拍照键：仅待机/聆听可用，当前=${_state.value}")
            return
        }
        pendingPhotoRetryJob?.cancel()
        when (_state.value) {
            ConversationState.IDLE -> sendPhotoWakeFromStandby()
            ConversationState.LISTENING -> {
                pendingPhotoFromStandby = false
                sendPhotoTextInConversation()
            }
            ConversationState.CONNECTING,
            ConversationState.PROCESSING,
            ConversationState.SPEAKING,
            -> Log.w(PhotoKeyLog.TAG, "拍照键：状态门禁未拦住，当前=${_state.value}")
        }
    }

    private fun tryHandlePendingPhotoKey() {
        if (!pendingPhotoFromStandby) return
        if (!conversationUiActive) {
            Log.d(PhotoKeyLog.TAG, "pendingPhoto: 对话页未就绪")
            schedulePhotoKeyRetry()
            return
        }
        if (!_isConnected.value) {
            Log.d(PhotoKeyLog.TAG, "pendingPhoto: 等待 MQTT 连接")
            schedulePhotoKeyRetry()
            return
        }
        when (_state.value) {
            ConversationState.CONNECTING -> {
                _state.value = ConversationState.IDLE
            }
            ConversationState.SPEAKING -> {
                cancelPendingPhotoKey("SPEAKING 不可拍照")
                return
            }
            ConversationState.PROCESSING -> {
                if (XiaozhiAppEvents.isPhotoSessionActive()) return
                cancelPendingPhotoKey("PROCESSING 不可拍照")
                return
            }
            ConversationState.IDLE,
            ConversationState.LISTENING,
            -> Unit
        }
        if (_state.value != ConversationState.IDLE && _state.value != ConversationState.LISTENING) {
            Log.d(PhotoKeyLog.TAG, "pendingPhoto: 等待 IDLE/LISTENING，当前=${_state.value}")
            schedulePhotoKeyRetry()
            return
        }
        if (pendingSessionEnd || pendingVoiceWake || isWakeHandoffInProgress()) {
            Log.d(PhotoKeyLog.TAG, "pendingPhoto: 交接中，稍后重试")
            schedulePhotoKeyRetry()
            return
        }
        pendingPhotoFromStandby = false
        pendingPhotoRetryJob?.cancel()
        if (_state.value == ConversationState.LISTENING) {
            sendPhotoTextInConversation()
        } else {
            executePhotoWakeDetect()
        }
    }

    private fun schedulePhotoKeyRetry() {
        if (!pendingPhotoFromStandby) return
        if (pendingPhotoRetryCount >= MAX_PHOTO_KEY_RETRIES) {
            Log.w(PhotoKeyLog.TAG, "拍照键：多次重试失败（$MAX_PHOTO_KEY_RETRIES 次）")
            cancelPendingPhotoKey("重试次数用尽")
            return
        }
        if (pendingPhotoRetryJob?.isActive == true) return
        pendingPhotoRetryCount++
        pendingPhotoRetryJob = viewModelScope.launch {
            delay(300L * pendingPhotoRetryCount)
            tryHandlePendingPhotoKey()
        }
    }

    private fun prepareCameraForPhotoSession() {
        SharedCameraCapture.dashcamSession = null
        SharedCameraCapture.forceReset()
        QuickPhotoCapture.preWarm(getApplication())
        Log.d(PhotoKeyLog.TAG, "拍照会话：已预热 MCP 相机")
    }

    private fun sendPhotoWakeFromStandby() {
        if (pendingSessionEnd || pendingVoiceWake || isWakeHandoffInProgress()) {
            Log.d(PhotoKeyLog.TAG, "待机拍照：交接/结束语中，忽略")
            return
        }
        markPendingPhotoFromStandby()
        if (!_isConnected.value) {
            connect()
            Log.d(PhotoKeyLog.TAG, "待机拍照：等待 MQTT 连接")
        }
        tryHandlePendingPhotoKey()
    }

    private fun executePhotoWakeDetect() {
        if (!_isConnected.value) {
            markPendingPhotoFromStandby()
            schedulePhotoKeyRetry()
            return
        }
        if (_state.value == ConversationState.CONNECTING) {
            _state.value = ConversationState.IDLE
        }
        if (_state.value != ConversationState.IDLE) {
            cancelPendingPhotoKey("待机拍照时 state=${_state.value}")
            schedulePhotoKeyRetry()
            return
        }
        prepareCameraForPhotoSession()
        pauseWakeListening()
        pendingAutoStart = false
        // 待机拍照不要开 autoMode，否则中间 TTS 结束会误进「聆听中」
        isAutoMode = false
        photoStartedFromStandby = true
        hideNextSttEcho = true
        XiaozhiAppEvents.beginPhotoSession()
        schedulePhotoMcpWait(ConversationState.IDLE)
        transitionState(ConversationState.PROCESSING, "photo_wake_detect")
        // 打断可能正在播的提示音，避免与拍照结果 TTS 叠成「重复播放」
        audioManager.stopPlaying()
        mqttManager.sendAbort("photo_key")
        mqttManager.sendWakeWordDetected("拍照")
        mqttManager.sendStopListening()
        Log.i(PhotoKeyLog.TAG, "待机拍照键：已发送「拍照」给服务器，等待 MCP take_photo → detectImageFile")
        VoiceFlowLog.snapshot("photoKey.standby", "send 拍照 detect → MCP → upload")
    }

    private fun sendPhotoTextInConversation() {
        if (!_isConnected.value) {
            Log.d(PhotoKeyLog.TAG, "聆听拍照：未连接")
            markPendingPhotoFromStandby()
            connect()
            schedulePhotoKeyRetry()
            return
        }
        if (_state.value != ConversationState.LISTENING) {
            Log.d(PhotoKeyLog.TAG, "聆听拍照：状态不可用 state=${_state.value}")
            cancelPendingPhotoKey("聆听拍照时 state=${_state.value}")
            return
        }
        prepareCameraForPhotoSession()
        audioManager.stopRecording()
        audioManager.stopPlaying()
        pendingAutoStart = false
        isAutoMode = true
        photoStartedFromStandby = false
        hideNextSttEcho = true
        XiaozhiAppEvents.beginPhotoSession()
        schedulePhotoMcpWait(ConversationState.LISTENING)
        transitionState(ConversationState.PROCESSING, "photo_key_text")
        // 排空上行残留后再发 detect，避免迟来 STT（如「对。」）冲掉拍照意图；
        // 协议与待机一致（无 source=text），便于服务端下发 MCP
        viewModelScope.launch {
            delay(150)
            if (!XiaozhiAppEvents.isPhotoSessionActive()) return@launch
            mqttManager.sendAbort("photo_key")
            mqttManager.sendWakeWordDetected("拍照")
            mqttManager.sendStopListening()
            Log.i(
                PhotoKeyLog.TAG,
                "聆听拍照键：已发送「拍照」detect（同待机），等待 MCP take_photo → detectImageFile",
            )
            VoiceFlowLog.snapshot("photoKey.listening", "send 拍照 detect → MCP → upload")
        }
    }

    private fun stopConversationFromVoiceKey() {
        cancelListenHandoff("voice_key_stop")
        pendingRecordKeyStart = false
        pendingVoiceWake = false
        pendingAutoStart = false
        shouldResumeOnUiReturn = false
        cancelSessionEndFallback()
        cancelSessionEndStandby()
        pendingSessionEnd = false
        sessionEndAudioReceived = false
        sessionEndTtsStopSeen = false
        sessionEndReconnectPending = false
        XiaozhiAppEvents.acknowledgeVoiceKeyEvent()
        cancelSpeakingWatchdog()
        isAutoMode = false
        audioManager.stopRecording()
        audioManager.stopPlaying()
        if (_isConnected.value) {
            mqttManager.sendStopListening()
            mqttManager.sendAbort("voice_key_stop")
        }
        stopListeningKeepalive()
        _state.value = ConversationState.IDLE
        Log.i(TAG, "录音键：结束对话 → 待机")
        VoiceFlowLog.snapshot("voiceKey.stop", "→待机 | ${flowContext()}")
        prepareStandbyWakeListening()
    }

    /** 录音键在「处理中/说话中」按下：打断当前回复，重新进入聆听 */
    @SuppressLint("MissingPermission")
    private fun restartListeningFromVoiceKey() {
        abortPhotoSessionForVoiceInput("voice_key_interrupt")
        cancelSpeakingWatchdog()
        cancelSessionEndFallback()
        cancelSessionEndStandby()
        pendingSessionEnd = false
        sessionEndAudioReceived = false
        sessionEndTtsStopSeen = false
        sessionEndReconnectPending = false
        audioManager.stopPlaying()
        if (_isConnected.value) {
            mqttManager.sendAbort("voice_key_interrupt")
        }
        isAutoMode = true
        pendingAutoStart = false
        transitionState(ConversationState.IDLE, "voice_key_interrupt")
        Log.i(TAG, "录音键：打断回复 → 重新聆听")
        if (!startAutoConversation()) {
            pendingAutoStart = true
            Log.w(TAG, "录音键打断：开麦未就绪，待连接后重试")
        }
    }

    private fun prepareForRecordKeySession() {
        audioManager.stopPlaying()
        audioManager.stopRecording()
        audioManager.releaseRecorderOnly()
        isAudioInitialized = false
        XiaozhiWakeForegroundService.releaseMicrophoneForConversation(getApplication())
        XiaozhiWakeCoordinator.clearWakeHandoff("record_key")
    }

    private fun tryHandlePendingRecordKeyStart() {
        if (!pendingRecordKeyStart && !XiaozhiAppEvents.hasPendingVoiceKeyPress()) return
        pendingRecordKeyStart = true
        if (!conversationUiActive) {
            Log.d(TAG, "录音键：对话页未就绪")
            scheduleRecordKeyRetry()
            return
        }
        if (!_isConnected.value) {
            Log.d(TAG, "录音键：等待连接")
            _state.value = ConversationState.CONNECTING
            connect()
            scheduleRecordKeyRetry()
            return
        }
        if (_state.value == ConversationState.CONNECTING) {
            scheduleRecordKeyRetry()
            return
        }
        if (_state.value != ConversationState.IDLE) {
            Log.w(TAG, "录音键：非 IDLE 状态 ${_state.value}，放弃开麦")
            pendingRecordKeyStart = false
            return
        }
        XiaozhiWakeForegroundService.releaseMicrophoneForConversation(getApplication())
        pauseWakeListening()
        if (!isAudioInitialized || !audioManager.isReady()) {
            initializeAudio()
            if (!isAudioInitialized || !audioManager.isReady()) {
                scheduleRecordKeyRetry()
                return
            }
        }
        if (startAutoConversation()) {
            Log.i(TAG, "录音键：开麦交接已启动")
        } else {
            Log.w(TAG, "录音键：开麦失败，稍后重试")
            scheduleRecordKeyRetry()
        }
    }

    private fun scheduleRecordKeyRetry() {
        if (!pendingRecordKeyStart && !XiaozhiAppEvents.hasPendingVoiceKeyPress()) return
        if (pendingRecordKeyRetryCount >= 12) {
            Log.w(TAG, "录音键：多次重试失败")
            return
        }
        pendingRecordKeyRetryCount++
        pendingRecordKeyRetryJob?.cancel()
        pendingRecordKeyRetryJob = viewModelScope.launch {
            delay(300L * pendingRecordKeyRetryCount)
            if (pendingRecordKeyStart || XiaozhiAppEvents.hasPendingVoiceKeyPress()) {
                tryHandlePendingRecordKeyStart()
            }
        }
    }

    /**
     * 检测到「你好，智询」后：连接小智并进入自动对话。
     */
    fun onVoiceWakeDetected() {
        sessionManager.clearUserStandbyDisconnect()
        Log.i(TAG, "onVoiceWakeDetected 关键词=${WakePhraseMatcher.WAKE_PHRASE}")
        logFlow("wake.detected", "关键词=${WakePhraseMatcher.WAKE_PHRASE}")
        abortPhotoSessionForVoiceInput("voice_wake")
        _isSessionEndStandby.value = false
        wakeConversationHandoff = true
        isAutoMode = true
        pendingVoiceWake = true
        pendingRecordKeyStart = false
        pendingWakeRetryCount = 0
        wakeDetectSentThisRound = false
        pendingWakeGreetingRefresh = false
        wakeGreetingRefreshAttempts = 0
        wakeGreetingFailedThisRound = false
        lastUplinkAudioAtMs = 0L
        hasLoggedFirstAudioFrame = false
        shouldResumeOnUiReturn = false
        resumeManualListening = false
        if (!XiaozhiWakeCoordinator.hasServerGreetingTtsPending() &&
            !wakeGreetingTtsStartSeen &&
            !_isWakeGreetingPlaying.value
        ) {
            audioManager.stopPlaying()
        } else {
            Log.d(TAG, "服务端问候 TTS 已开始，保留播放链路")
        }
        audioManager.stopRecording()
        _state.value = ConversationState.IDLE
        pauseWakeListening()
        XiaozhiWakeForegroundService.releaseMicrophoneForConversation(getApplication())
        audioManager.releaseRecorderOnly()
        isAudioInitialized = false
        initializeAudio()
        connect()
        updateWakeHandoffUi()
        tryHandlePendingVoiceWake()
    }

    private fun tryHandlePendingVoiceWake() {
        if (!pendingVoiceWake) return
        if (!conversationUiActive) {
            VoiceFlowLog.decision("wake.pending", "开麦", false, "对话页未就绪")
            Log.d(TAG, "pendingWake: 对话页未就绪")
            updateWakeHandoffUi()
            return
        }
        if (!_isConnected.value) {
            VoiceFlowLog.decision("wake.pending", "开麦", false, "MQTT 未连接")
            Log.d(TAG, "pendingWake: MQTT 未连接")
            updateWakeHandoffUi()
            return
        }
        if (_state.value != ConversationState.IDLE) {
            if (isWakeGreetingWindow() || wakeGreetingTtsStartSeen || _isWakeGreetingPlaying.value) {
                Log.d(TAG, "pendingWake: 问候 TTS 已开始 state=${_state.value}，不 abort")
            } else {
                VoiceFlowLog.warn("wake.pending", "非 IDLE(${_state.value})，重置后重试")
                Log.d(TAG, "pendingWake: 状态=${_state.value}，重置为 IDLE")
                audioManager.stopPlaying()
                audioManager.stopRecording()
                mqttManager.sendAbort("wake_handoff")
                transitionState(ConversationState.IDLE, "pendingWake_reset")
            }
        }
        if (!ensureAudioReadyForPendingWake()) {
            VoiceFlowLog.decision("wake.pending", "开麦", false, "音频未就绪")
            updateWakeHandoffUi()
            return
        }

        pendingVoiceWake = false
        pendingWakeRetryJob?.cancel()
        pendingWakeRetryJob = null
        pendingWakeRetryCount = 0
        pauseWakeListening()
        showWakePhraseAsUserMessage(WakePhraseMatcher.WAKE_PHRASE)
        scheduleWakeGreetingSuppression(
            preserveProgress = wakeGreetingTtsStartSeen || wakeGreetingAudioReceived,
        )
        ensureDownlinkPlaybackReady(forceReprepare = true)
        wakeDetectSentThisRound = false
        if (XiaozhiWakeCoordinator.hasServerGreetingTtsPending() || wakeGreetingTtsStartSeen) {
            XiaozhiWakeCoordinator.clearServerGreetingTtsPending()
            mqttManager.startWakeGreetingNatKeepalive()
            if (!wakeGreetingTtsStartSeen) {
                markWakeGreetingTtsStart()
            }
            Log.i(TAG, "pendingWake → 沿用 WakeSTT 触发的问候 TTS，不发送 detect")
            logFlow("wake.detect.skip", "server_stt_greeting")
        } else {
            // 离线 KWS：问候阶段无麦克风上行，需静音 Opus 保活 NAT，否则只有 tts 信令无声音
            mqttManager.startWakeGreetingNatKeepalive()
            mqttManager.sendWakeWordDetected(WakePhraseMatcher.WAKE_PHRASE)
            wakeDetectSentThisRound = true
            Log.i(TAG, "pendingWake → 无服务端问候，立即发送 detect")
            logFlow("wake.detect.send", "offline_or_no_server_greeting")
        }
        startAutoConversation()
    }

    private fun scheduleWakeGreetingSuppression(preserveProgress: Boolean = false) {
        suppressWakeGreetingUntilMs = System.currentTimeMillis() + WAKE_GREETING_SUPPRESS_MS
        if (!preserveProgress) {
            wakeGreetingAudioReceived = false
            wakeGreetingTtsStartSeen = false
            wakeGreetingTtsStopSeen = false
            wakeGreetingPhaseComplete = false
            wakeGreetingListenActive = false
        }
        VoiceFlowLog.step(
            "wake.greetingWindow",
            "开启 ${WAKE_GREETING_SUPPRESS_MS}ms，until=$suppressWakeGreetingUntilMs preserve=$preserveProgress",
        )
    }

    /** detect 后一段时间内视为唤醒问候窗口：tts start→SPEAKING 播放，tts stop 后再 listen/start */
    private fun isWakeGreetingWindow(): Boolean =
        System.currentTimeMillis() < suppressWakeGreetingUntilMs

    private fun clearWakeGreetingSuppression() {
        if (suppressWakeGreetingUntilMs > 0L) {
            VoiceFlowLog.step("wake.greetingWindow", "清除")
        }
        suppressWakeGreetingUntilMs = 0L
        wakeGreetingAudioReceived = false
        wakeGreetingTtsStartSeen = false
        wakeGreetingTtsStopSeen = false
        wakeGreetingPhaseComplete = false
        wakeGreetingListenActive = false
        XiaozhiWakeCoordinator.clearServerGreetingTtsPending()
        setWakeGreetingPlaying(false)
    }

    /** 仅 WakeSTT 命中唤醒词后的交接期才提前进入问候窗口 */
    private fun shouldArmWakeGreetingFromServerTts(): Boolean =
        XiaozhiWakeCoordinator.hasServerGreetingTtsPending() &&
            XiaozhiWakeCoordinator.isWakeHandoffInProgress()

    /** WakeSTT 命中唤醒词后、onVoiceWakeDetected 之前，server tts 可能已到达 */
    private fun armWakeGreetingFromServerTtsIfNeeded() {
        if (!shouldArmWakeGreetingFromServerTts()) return
        if (isWakeGreetingWindow()) return
        scheduleWakeGreetingSuppression()
        if (_messages.value.none {
                it.role == MessageRole.USER &&
                    WakePhraseMatcher.matches(it.content)
            }
        ) {
            showWakePhraseAsUserMessage(WakePhraseMatcher.WAKE_PHRASE)
        }
        ensureDownlinkPlaybackReady(forceReprepare = true)
        wakeConversationHandoff = true
        isAutoMode = true
        XiaozhiWakeCoordinator.clearServerGreetingTtsPending()
    }

    /**
     * 仅交接期压制服务器回显。
     * 勿单独用 greetWindow：开麦后窗口可能残留约 30s，会造成偶发「聆听中不识别」。
     */
    private fun shouldSuppressWakeHandoffEcho(): Boolean =
        wakeConversationHandoff ||
            pendingVoiceWake ||
            listenHandoffJob?.isActive == true ||
            XiaozhiWakeCoordinator.isWakeHandoffInProgress()

    /** 仅过滤唤醒词/交接回显，不过滤问候窗口内用户真实提问 */
    private fun shouldSuppressWakeSttEcho(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return true
        if (WakePhraseMatcher.matches(t)) return true
        return isWakeHandoffInProgress() || pendingVoiceWake
    }

    /**
     * 「单个字 + 符号」的 STT 噪声不送对话（如「嗯。」「啊！」）。
     * 仅「好」及其带标点形式放行；无标点单字、多字句、退下/唤醒等走原逻辑。
     */
    private fun shouldIgnoreSingleCharSymbolStt(text: String): Boolean {
        var content: Char? = null
        var symbolCount = 0
        for (ch in text.trim()) {
            if (ch.isWhitespace() || isSttNoiseSymbol(ch)) {
                symbolCount++
                continue
            }
            if (content != null) return false
            content = ch
        }
        if (content == null || symbolCount == 0) return false
        return content != '好'
    }

    private fun isSttNoiseSymbol(ch: Char): Boolean {
        if (ch.isLetterOrDigit()) return false
        // CJK 统一表意文字等视为内容字
        val type = Character.getType(ch).toByte()
        if (type == Character.OTHER_LETTER) return false
        return when (type) {
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            Character.MATH_SYMBOL,
            Character.CURRENCY_SYMBOL,
            Character.MODIFIER_SYMBOL,
            Character.OTHER_SYMBOL,
            -> true
            else -> !ch.isLetterOrDigit()
        }
    }

    private fun shouldAcceptWakeGreetingTtsStop(): Boolean =
        // 必须已收到问候 Opus。勿用 isPlaying()/greetWindow：管线空转或仅 start 也会误判
        wakeGreetingAudioReceived

    private fun armPostWakeSpuriousTtsSuppress(reason: String, durationMs: Long = 4_000L) {
        suppressPostWakeSpuriousTtsUntilMs =
            maxOf(suppressPostWakeSpuriousTtsUntilMs, System.currentTimeMillis() + durationMs)
        VoiceFlowLog.step("wake.spuriousTts", "arm ${durationMs}ms reason=$reason")
    }

    private fun clearPostWakeSpuriousTtsSuppress(reason: String) {
        if (suppressPostWakeSpuriousTtsUntilMs == 0L) return
        suppressPostWakeSpuriousTtsUntilMs = 0L
        VoiceFlowLog.step("wake.spuriousTts", "clear reason=$reason")
    }

    private fun shouldSuppressPostWakeSpuriousTts(): Boolean =
        System.currentTimeMillis() < suppressPostWakeSpuriousTtsUntilMs

    private fun abortPostWakeSpuriousTts(reason: String) {
        armPostWakeSpuriousTtsSuppress(reason)
        audioManager.stopPlaying()
        mqttManager.sendAbort(reason)
        Log.i(TAG, "打断开麦后多余 TTS reason=$reason")
        VoiceFlowLog.warn("wake.spuriousTts", "abort reason=$reason")
    }

    private fun clearWakeConversationHandoff(reason: String) {
        if (!wakeConversationHandoff &&
            !XiaozhiWakeCoordinator.isWakeHandoffInProgress()
        ) {
            return
        }
        wakeConversationHandoff = false
        XiaozhiWakeCoordinator.clearWakeHandoff(reason)
        updateWakeHandoffUi()
        logFlow("wake.handoff.clear", "reason=$reason")
    }

    private fun showWakePhraseAsUserMessage(phrase: String) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return
        val last = _messages.value.lastOrNull()
        if (last?.role == MessageRole.USER && last.content.trim() == trimmed) return
        addMessage(Message(role = MessageRole.USER, content = trimmed))
        currentUserMessage = trimmed
        Log.d(TAG, "展示唤醒词: $trimmed")
    }

    private fun pauseWakeListening() {
        XiaozhiWakeForegroundService.pauseListening(getApplication())
    }

    private fun resumeWakeListeningIfNeeded() {
        if (XiaozhiWakeForegroundService.isConversationMicClaimed()) {
            Log.d(TAG, "对话占用麦克风，不恢复唤醒监听")
            return
        }
        if (XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
            Log.d(TAG, "唤醒交接中，不恢复后台监听")
            return
        }
        if (XiaozhiAppEvents.isPhotoSessionActive()) {
            Log.d(PhotoKeyLog.TAG, "拍照会话中，不恢复后台监听")
            return
        }
        if (_state.value == ConversationState.LISTENING || pendingVoiceWake || isAutoMode) {
            return
        }
        if (XiaozhiWakeForegroundService.isWakeListeningHealthy()) {
            Log.d(TAG, "唤醒监听已就绪，跳过恢复")
            updateStandbyReady()
            return
        }
        if (conversationUiActive && _state.value != ConversationState.IDLE) {
            Log.d(TAG, "对话页进行中，不恢复唤醒监听")
            return
        }
        Log.d(TAG, "恢复语音唤醒监听 state=${_state.value} ui=$conversationUiActive")
        audioManager.stopRecording()
        audioManager.stopPlaying()
        audioManager.releaseRecorderOnly()
        isAudioInitialized = false
        XiaozhiWakeForegroundService.ensureListeningActive(getApplication())
        updateStandbyReady()
        scheduleStandbyReadyPoll()
    }

    /** 对话页待机时不占用麦克风，留给唤醒服务 */
    private fun shouldDeferMicForWakeListening(): Boolean {
        if (pendingVoiceWake || pendingRecordKeyStart || XiaozhiAppEvents.hasPendingVoiceKeyPress()) {
            return false
        }
        if (isAutoMode || _state.value != ConversationState.IDLE) {
            return false
        }
        return conversationUiActive
    }

    /**
     * 对话页就绪：连接 MQTT；待机时不抢唤醒 mic。
     */
    fun onConversationScreenReady() {
        connect()
        if (pendingPhotoFromStandby) {
            tryHandlePendingPhotoKey()
            updateStandbyReady()
            return
        }
        if (pendingVoiceWake || pendingRecordKeyStart || XiaozhiAppEvents.hasPendingVoiceKeyPress()) {
            initializeAudio()
            if (pendingRecordKeyStart || XiaozhiAppEvents.hasPendingVoiceKeyPress()) {
                tryHandlePendingRecordKeyStart()
            }
            updateStandbyReady()
            return
        }
        prepareStandbyWakeListening()
    }

    private fun prepareStandbyWakeListening() {
        audioManager.releaseRecorderOnly()
        isAudioInitialized = false
        if (!audioManager.isPlaybackReady()) {
            audioManager.initializePlaybackOnly()
        }
        XiaozhiWakeForegroundService.ensureStarted(getApplication())
        XiaozhiWakeForegroundService.ensureListeningActive(getApplication())
        Log.d(TAG, "对话页待机：麦克风留给唤醒服务")
        logFlow("standby.prepareWake", "ensureListeningActive 已调用")
        updateStandbyReady()
        scheduleStandbyReadyPoll()
    }

    /** 待机黑屏（[ScreenOnHelper] 20s）：断开小智、释放相机，保留语音唤醒 */
    fun onStandbyScreenSleep() {
        if (_isStandbyScreenSleep.value) return
        if (!isStandbySleepEligible()) {
            Log.d(PhotoKeyLog.TAG, "跳过休眠断连：state=${_state.value} auto=$isAutoMode")
            return
        }
        _isStandbyScreenSleep.value = true
        standbyReconnectGraceJob?.cancel()
        _isStandbyReconnecting.value = false
        _isAwaitingReconnect.value = false

        if (XiaozhiAppEvents.isPhotoSessionActive()) {
            resetPhotoFlowToInitialStandby("standby_sleep")
        } else {
            XiaozhiMcpHandler.abortStuckCapture("standby_sleep")
            XiaozhiMcpHandler.cancelTakePhotoFallback()
            SharedCameraCapture.forceReset()
        }

        if (_isConnected.value || sessionManager.isConnecting.value) {
            sessionManager.disconnectForStandbySleep()
        } else {
            sessionManager.mqttManager.disableReconnect()
        }
        _isConnected.value = false

        prepareStandbyWakeListening()
        Log.i(TAG, "待机休眠：已断连并释放相机，唤醒服务保持运行")
        VoiceFlowLog.snapshot("standby.sleep", "disconnect ws, camera reset, wake kept")
        updateStandbyReady()
    }

    /** 亮屏/触摸恢复亮度：保持唤醒待机，不自动重连 MQTT */
    fun onStandbyScreenWake(fromSleep: Boolean) {
        if (!_isStandbyScreenSleep.value) return
        Log.i(TAG, "亮屏恢复：保持唤醒待机，不重连 fromSleep=$fromSleep")
        VoiceFlowLog.snapshot("standby.wake", "wakeStandbyNoReconnect fromSleep=$fromSleep")
        clearStandbyScreenSleep("screen_wake")
        prepareStandbyWakeListening()
        updateStandbyReady()
    }

    private fun isStandbySleepEligible(): Boolean {
        if (!conversationUiActive) return false
        if (isAutoMode || pendingVoiceWake || pendingSessionEnd) return false
        if (XiaozhiWakeCoordinator.isWakeHandoffInProgress()) return false
        return when (_state.value) {
            ConversationState.IDLE -> true
            ConversationState.CONNECTING ->
                _isStandbyReconnecting.value ||
                    _isSessionEndStandby.value ||
                    _isAwaitingReconnect.value
            else -> false
        }
    }

    private fun clearStandbyScreenSleep(reason: String) {
        if (!_isStandbyScreenSleep.value) return
        _isStandbyScreenSleep.value = false
        Log.d(TAG, "清除待机休眠标记 reason=$reason")
    }

    private fun cancelSessionEndFallback() {
        sessionEndFallbackJob?.cancel()
        sessionEndFallbackJob = null
    }

    private fun cancelSessionEndStandby() {
        sessionEndStandbyJob?.cancel()
        sessionEndStandbyJob = null
    }

    private fun scheduleSessionEndFallback() {
        cancelSessionEndFallback()
        sessionEndFallbackJob = viewModelScope.launch {
            delay(20_000)
            if (!pendingSessionEnd) return@launch
            Log.w(TAG, "结束语等待超时，强制进入唤醒待机")
            Log.w(SESSION_END_TAG, "20s 内未收到完整结束语，强制进入唤醒待机")
            scheduleSessionEndCompletion("session_end_timeout")
        }
    }

    /** 结束语 tts stop 后等待 AudioTrack 播完，再关闭任务并进入唤醒待机（不重连） */
    private fun scheduleSessionEndCompletion(trigger: String) {
        if (sessionEndStandbyJob?.isActive == true) return
        sessionEndStandbyJob = viewModelScope.launch {
            cancelSessionEndFallback()
            logFlow("sessionEnd.awaitPlayback", "trigger=$trigger play=${audioManager.isPlaying()}")
            Log.i(SESSION_END_TAG, "等待结束语播完 trigger=$trigger")
            awaitSessionEndPlayback()
            if (!pendingSessionEnd && trigger != "session_end_timeout") return@launch
            finalizeSessionEndAndStandby(trigger)
        }
    }

    private suspend fun awaitSessionEndPlayback(timeoutMs: Long = 10_000L) {
        if (!sessionEndAudioReceived && !audioManager.isPlaying()) {
            delay(200)
            return
        }
        try {
            withTimeout(6_000L) {
                audioManager.waitForPlaybackCompletion()
            }
            delay(200)
            Log.d(TAG, "结束语播放完成（waitForPlaybackCompletion）")
            return
        } catch (_: TimeoutCancellationException) {
            Log.d(TAG, "结束语 waitForPlaybackCompletion 超时，轮询播放状态")
        }
        var idleSince = 0L
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!audioManager.isPlaying()) {
                if (idleSince == 0L) idleSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - idleSince >= 300) {
                    Log.d(TAG, "结束语播放完成（轮询 isPlaying）")
                    return
                }
            } else {
                idleSince = 0L
            }
            delay(50)
        }
        Log.w(TAG, "结束语播放等待超时，仍进入唤醒待机")
    }

    /** 关闭对话相关任务，断开 MQTT，进入唤醒待机（不重连） */
    private fun finalizeSessionEndAndStandby(trigger: String) {
        shutdownAllConversationTasks()
        pendingSessionEnd = false
        sessionEndAudioReceived = false
        sessionEndTtsStopSeen = false
        audioManager.stopRecording()
        audioManager.stopPlaying()
        audioManager.releaseRecorderOnly()
        isAudioInitialized = false
        XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
        clearWakeConversationHandoff("session_end")
        Log.i(TAG, "结束语完成($trigger) → 唤醒待机，不重连")
        Log.i(SESSION_END_TAG, "结束语播完，断开 MQTT，进入唤醒待机")
        logFlow("sessionEnd.complete", "trigger=$trigger wakeStandbyNoReconnect")
        sessionManager.disconnectForUserStandby()
        _isAwaitingReconnect.value = false
        _isStandbyReconnecting.value = false
        _isSessionEndStandby.value = false
        sessionEndReconnectPending = false
        enterStandby("session_end:$trigger", notifyServer = false)
        sessionEndStandbyJob = null
    }

    private fun enterWakeStandbyWithoutReconnect(reason: String) {
        sessionManager.suppressReconnectForWakeStandby()
        _isAwaitingReconnect.value = false
        _isStandbyReconnecting.value = false
        standbyReconnectGraceJob?.cancel()
        sessionEndReconnectPending = false
        enterStandby(reason, notifyServer = false)
    }

    private fun shutdownAllConversationTasks() {
        cancelListenHandoff("shutdown_tasks")
        cancelSpeakingWatchdog()
        clearAssistantReplyTurn("shutdown_tasks")
        cancelSessionEndFallback()
        stopListeningKeepalive()
        standbyReadyPollJob?.cancel()
        standbyReadyPollJob = null
        pendingWakeRetryJob?.cancel()
        pendingWakeRetryJob = null
        pendingRecordKeyRetryJob?.cancel()
        pendingRecordKeyRetryJob = null
        pendingAutoStart = false
        pendingVoiceWake = false
        pendingRecordKeyStart = false
        isAutoMode = false
        wakeConversationHandoff = false
        setWakeGreetingPlaying(false)
        clearWakeGreetingSuppression()
    }

    private fun cancelSpeakingWatchdog() {
        speakingWatchdogJob?.cancel()
        speakingWatchdogJob = null
    }

    private fun markAssistantReplyActive(reason: String) {
        if (isWakeGreetingTurn() || pendingSessionEnd ||
            XiaozhiAppEvents.isPhotoSessionActive() || photoRoundAwaitingTtsFinish
        ) {
            return
        }
        if (!assistantReplyActive) {
            Log.i(TAG, "助手回答回合开始 reason=$reason")
        }
        assistantReplyActive = true
        assistantReplyTtsStopSeen = false
        cancelAssistantReplyDrain()
    }

    private fun clearAssistantReplyTurn(reason: String) {
        if (!assistantReplyActive && !assistantReplyAudioReceived &&
            !assistantReplyTtsStopSeen && assistantReplyDrainJob == null
        ) {
            return
        }
        Log.i(TAG, "助手回答回合结束 reason=$reason")
        assistantReplyActive = false
        assistantReplyAudioReceived = false
        assistantReplyTtsStopSeen = false
        cancelAssistantReplyDrain()
    }

    private fun cancelAssistantReplyDrain() {
        assistantReplyDrainJob?.cancel()
        assistantReplyDrainJob = null
    }

    private fun shouldDeferAssistantReplyFinish(): Boolean {
        if (pendingSessionEnd) return false
        if (isWakeGreetingTurn()) return false
        if (XiaozhiAppEvents.isPhotoSessionActive() ||
            photoRoundAwaitingTtsFinish ||
            stateBeforePhotoRound != null ||
            photoStartedFromStandby
        ) {
            return false
        }
        return assistantReplyActive ||
            _state.value == ConversationState.SPEAKING ||
            audioManager.isPlaying()
    }

    /**
     * 普通多句 TTS：每句 stop 后先等播完；若又来 start 则取消排空继续说。
     * 避免长回答中间 stop 立刻回「聆听中」并丢掉后续文案。
     */
    private fun scheduleAssistantReplyDrain(trigger: String) {
        if (assistantReplyDrainJob?.isActive == true) return
        assistantReplyDrainJob = viewModelScope.launch {
            Log.i(
                TAG,
                "助手回答排空等待 trigger=$trigger play=${audioManager.isPlaying()} " +
                    "audioRecv=$assistantReplyAudioReceived",
            )
            if (!assistantReplyAudioReceived && !audioManager.isPlaying()) {
                delay(400)
            }
            var idleSince = 0L
            val deadline = System.currentTimeMillis() + SPEAKING_WATCHDOG_MS
            while (System.currentTimeMillis() < deadline) {
                if (!assistantReplyTtsStopSeen) {
                    // 续句 start 已取消 stopSeen：本轮排空作废
                    Log.d(TAG, "助手回答排空取消（续句中）")
                    return@launch
                }
                if (audioManager.isPlaying()) {
                    idleSince = 0L
                } else {
                    if (idleSince == 0L) idleSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - idleSince >= ASSISTANT_REPLY_IDLE_MS) {
                        break
                    }
                }
                delay(50)
            }
            assistantReplyDrainJob = null
            clearAssistantReplyTurn("drain_$trigger")
            finishSpeakingTurn(trigger)
        }
    }

    private fun scheduleSpeakingWatchdog() {
        if (XiaozhiAppEvents.isPhotoSessionActive() &&
            _state.value == ConversationState.PROCESSING
        ) {
            VoiceFlowLog.step("tts.watchdog", "跳过（等待 MCP/识别）")
            return
        }
        if (isWakeGreetingTurn() || _isWakeGreetingPlaying.value ||
            listenHandoffJob?.isActive == true || pendingSessionEnd
        ) {
            VoiceFlowLog.step("tts.watchdog", "跳过（唤醒问候/交接中）")
            return
        }
        cancelSpeakingWatchdog()
        val firstWait = if (assistantReplyAudioReceived) {
            SPEAKING_NO_AUDIO_MS
        } else {
            SPEAKING_FIRST_AUDIO_MS
        }
        VoiceFlowLog.step(
            "tts.watchdog",
            "启动 firstWait=${firstWait}ms total=${SPEAKING_WATCHDOG_MS}ms",
        )
        speakingWatchdogJob = viewModelScope.launch {
            delay(firstWait)
            if (_state.value != ConversationState.SPEAKING) return@launch
            if (assistantReplyDrainJob?.isActive == true) return@launch
            if (!audioManager.isPlaying() && !assistantReplyAudioReceived) {
                VoiceFlowLog.warn(
                    "tts.watchdog",
                    "${firstWait}ms 无音频播放 → finishSpeakingTurn | ${flowContext()}",
                )
                Log.w(TAG, "TTS ${firstWait}ms 无音频，恢复聆听")
                clearAssistantReplyTurn("watchdog_no_audio")
                audioManager.stopPlaying()
                finishSpeakingTurn("watchdog_no_audio")
                return@launch
            }
            var idleSince = 0L
            val deadline = System.currentTimeMillis() + SPEAKING_WATCHDOG_MS
            while (_state.value == ConversationState.SPEAKING &&
                System.currentTimeMillis() < deadline
            ) {
                if (assistantReplyDrainJob?.isActive == true) return@launch
                if (audioManager.isPlaying()) {
                    idleSince = 0L
                } else if (assistantReplyTtsStopSeen) {
                    // 交给排空逻辑结束回合
                    return@launch
                } else {
                    if (idleSince == 0L) idleSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - idleSince >= SPEAKING_NO_AUDIO_MS) {
                        VoiceFlowLog.warn(
                            "tts.watchdog",
                            "播放空闲 ${SPEAKING_NO_AUDIO_MS}ms → finishSpeakingTurn",
                        )
                        clearAssistantReplyTurn("watchdog_idle")
                        audioManager.stopPlaying()
                        finishSpeakingTurn("watchdog_idle")
                        return@launch
                    }
                }
                delay(200)
            }
            if (_state.value != ConversationState.SPEAKING) return@launch
            if (audioManager.isPlaying() || assistantReplyAudioReceived) {
                Log.w(TAG, "TTS 绝对超时仍在播，改走排空")
                assistantReplyTtsStopSeen = true
                scheduleAssistantReplyDrain("watchdog_timeout_drain")
                return@launch
            }
            VoiceFlowLog.warn(
                "tts.watchdog",
                "${SPEAKING_WATCHDOG_MS}ms 未收到 stop → finishSpeakingTurn | ${flowContext()}",
            )
            Log.w(TAG, "TTS 超时未收到 stop，强制恢复")
            clearAssistantReplyTurn("watchdog_timeout")
            audioManager.stopPlaying()
            finishSpeakingTurn("watchdog_timeout")
        }
    }

    private fun finishSpeakingTurn(trigger: String = "tts_stop") {
        cancelSpeakingWatchdog()
        cancelAssistantReplyDrain()
        VoiceFlowLog.step("tts.finish", "trigger=$trigger | ${flowContext()}")
        if (photoRoundAwaitingTtsFinish) {
            // 结果已播完：短时静音，避免服务端紧接第二轮同内容 TTS
            photoPostResultMuteUntilMs = System.currentTimeMillis() + 3_000L
            audioManager.stopPlaying()
            restoreStateAfterPhotoRound("photo_tts_$trigger", requireTtsPending = true)
            return
        }
        if (shouldIgnoreStalePhotoTtsControl()) {
            Log.i(
                PhotoKeyLog.TAG,
                "忽略迟来 TTS finish（等待 MCP capture） trigger=$trigger state=${_state.value}",
            )
            audioManager.stopPlaying()
            return
        }
        // 拍照未结束：禁止因中间 TTS（如「正在拍照」）自动开麦变成「聆听中」
        if (XiaozhiAppEvents.isPhotoSessionActive() ||
            stateBeforePhotoRound != null ||
            photoStartedFromStandby
        ) {
            Log.i(
                PhotoKeyLog.TAG,
                "拍照轮次中忽略自动开麦 trigger=$trigger state=${_state.value} " +
                    "fromStandby=$photoStartedFromStandby",
            )
            audioManager.stopPlaying()
            if (_state.value == ConversationState.SPEAKING ||
                _state.value == ConversationState.LISTENING
            ) {
                transitionState(ConversationState.PROCESSING, "photo_wait_result_tts")
            }
            return
        }
        if (listenHandoffJob?.isActive == true && isWakeGreetingWindow()) {
            VoiceFlowLog.decision("tts.finish", "处理", false, "handoff 问候等待中")
            Log.d(TAG, "开麦交接问候等待中，忽略 TTS finish($trigger)")
            return
        }
        if (_state.value == ConversationState.IDLE &&
            (isWakeGreetingWindow() || isWakeHandoffInProgress())
        ) {
            if (shouldAcceptWakeGreetingTtsStop()) {
                wakeGreetingTtsStopSeen = true
                setWakeGreetingPlaying(false)
                wakeGreetingAudioReceived = false
                completeWakeGreetingPhase("idle_finish")
            }
            Log.d(TAG, "唤醒交接/问候窗口内忽略 IDLE TTS finish")
            return
        }
        if (shouldSuppressWakeHandoffEcho() && !isWakeGreetingWindow()) {
            VoiceFlowLog.decision("tts.finish", "处理 stop", false, "唤醒交接中")
            Log.d(TAG, "唤醒交接中，忽略 TTS stop")
            return
        }
        // 唤醒问候播完：保持 LISTENING 开麦，不打断仍在播放的音频
        if (_state.value == ConversationState.LISTENING &&
            isAutoMode &&
            !pendingSessionEnd &&
            audioManager.isRecording() &&
            isWakeGreetingTurn()
        ) {
            if (!shouldAcceptWakeGreetingTtsStop()) {
                Log.d(TAG, "忽略未开始播放的迟来问候 TTS finish")
                return
            }
            setWakeGreetingPlaying(false)
            wakeGreetingAudioReceived = false
            completeWakeGreetingPhase("finish_speaking_turn")
            Log.d(TAG, "唤醒问候播完，继续聆听")
            return
        }
        // 已在自动聆听时收到的 stop 多为上一轮 abort 后的迟来回显，保持开麦且不打断 TTS 播放
        if (shouldIgnoreStaleReplyWhileListening()) {
            if (_isWakeGreetingPlaying.value) {
                setWakeGreetingPlaying(false)
            }
            VoiceFlowLog.decision("tts.finish", "处理 stop", false, "聆听中忽略迟来 TTS stop")
            Log.d(TAG, "聆听中忽略迟来 TTS stop")
            return
        }
        if (pendingSessionEnd && !sessionEndTtsStopSeen) {
            VoiceFlowLog.decision("tts.finish", "处理", false, "结束语等待 tts stop")
            Log.d(TAG, "结束语等待中，忽略 TTS finish($trigger)")
            return
        }
        if (pendingSessionEnd) {
            sessionEndTtsStopSeen = true
            cancelSpeakingWatchdog()
            Log.i(TAG, "结束语 TTS stop → 等待播完再断开重连")
            Log.i(SESSION_END_TAG, "结束语 TTS stop，等待 AudioTrack 播完")
            logFlow("sessionEnd.ttsStop", "trigger=$trigger play=${audioManager.isPlaying()}")
            scheduleSessionEndCompletion(trigger)
            return
        }
        // 兜底：结束语已写入气泡但未武装 pendingSessionEnd 时，勿再开麦成假聆听
        val lastAssistant = _messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }?.content
        if (!lastAssistant.isNullOrBlank() &&
            armSessionEndFromAssistantFarewell(lastAssistant, "tts_finish_fallback")
        ) {
            sessionEndTtsStopSeen = true
            cancelSpeakingWatchdog()
            scheduleSessionEndCompletion("assistant_farewell_$trigger")
            return
        }
        if (isAutoMode && _isConnected.value && conversationUiActive &&
            (_state.value == ConversationState.SPEAKING ||
                _state.value == ConversationState.LISTENING ||
                _state.value == ConversationState.PROCESSING ||
                pendingVoiceWake ||
                wakeConversationHandoff ||
                XiaozhiWakeCoordinator.isWakeHandoffInProgress() ||
                listenHandoffJob?.isActive == true)
        ) {
            clearAssistantReplyTurn("finish_$trigger")
            audioManager.stopPlaying()
            if (_state.value == ConversationState.SPEAKING) {
                audioManager.stopRecording()
            }
            if (audioManager.isRecording()) {
                // TTS 期间服务端 listen 可能已停（偶发），必须重新 start
                renewListenSession("tts_finish_resume")
                transitionState(ConversationState.LISTENING, "tts_finish_resume_listening")
                scheduleListeningHealthCheck()
                Log.i(TAG, "TTS 结束 → 恢复聆听 (listen/start)")
                return
            }
            audioManager.stopRecording()
            transitionState(ConversationState.IDLE, "tts_finish_next_round")
            Log.i(TAG, "TTS 结束 → 下一轮聆听")
            if (!startAutoConversation()) {
                pendingAutoStart = true
                VoiceFlowLog.warn("tts.finish", "startAutoConversation 失败，pendingAutoStart=true")
            }
            return
        }
        clearAssistantReplyTurn("finish_standby_$trigger")
        audioManager.stopRecording()
        enterStandby("tts_end", notifyServer = false)
    }

    /**
     * 对话进行中断线是否重连恢复开麦。
     * - 唤醒交接/问候窗口/回复在途：必须重连，否则只显示「你好智询」无回复
     * - 刚开麦不久（<15s）断线：按异常断线重连（非服务端长静音超时）
     * - 聆听已久后被服务端断开：视为无语音输入超时 → 待机
     */
    private fun shouldReconnectAfterConversationDisconnect(): Boolean {
        if (!mqttManager.isAutoReconnectEnabled() || !conversationUiActive) return false
        if (pendingSessionEnd || !isAutoMode) return false
        if (isWakeGreetingWindow() ||
            wakeConversationHandoff ||
            XiaozhiWakeCoordinator.isWakeHandoffInProgress() ||
            listenHandoffJob?.isActive == true
        ) {
            return true
        }
        val state = _state.value
        if (state == ConversationState.PROCESSING || state == ConversationState.SPEAKING) {
            return true
        }
        if (state == ConversationState.LISTENING && lastAutoListeningStartedAtMs > 0L) {
            val listeningFor = System.currentTimeMillis() - lastAutoListeningStartedAtMs
            if (listeningFor < 15_000L) return true
        }
        return false
    }

    private fun beginWsReconnectAfterConversationDisconnect() {
        // 唤醒问候未完成/失败就断线：重连后应重新 detect，而不是裸开麦
        if (isWakeGreetingWindow() ||
            wakeConversationHandoff ||
            wakeGreetingFailedThisRound ||
            pendingWakeGreetingRefresh
        ) {
            pendingWakeGreetingRefresh = true
            pendingAutoStart = false
        } else {
            pendingAutoStart = true
        }
        isAutoMode = true
        XiaozhiWakeForegroundService.releaseMicrophoneForConversation(getApplication())
        _isAwaitingReconnect.value = true
        _isStandbyReady.value = false
        transitionState(ConversationState.CONNECTING, "ws_disconnect_conversation")
        Log.i(TAG, "对话中断连，重连后恢复自动对话")
        VoiceFlowLog.warn(
            "mqtt.disconnected",
            "对话中断连 pendingAutoStart=$pendingAutoStart refreshGreeting=$pendingWakeGreetingRefresh | ${flowContext()}",
        )
        updateStandbyReady()
        scheduleStandbyReadyPoll()
    }

    /** 问候 TTS 未到：断线换新会话后重新 detect */
    private fun requestWakeGreetingSessionRefresh(reason: String): Boolean {
        if (wakeGreetingRefreshAttempts >= 1) {
            VoiceFlowLog.warn("wake.greeting.refresh", "exhausted reason=$reason")
            return false
        }
        wakeGreetingRefreshAttempts++
        pendingWakeGreetingRefresh = true
        pendingAutoStart = false
        isAutoMode = true
        wakeConversationHandoff = true
        wakeDetectSentThisRound = false
        scheduleWakeGreetingSuppression(preserveProgress = false)
        audioManager.stopPlaying()
        audioManager.stopRecording()
        _isAwaitingReconnect.value = true
        _isStandbyReady.value = false
        transitionState(ConversationState.CONNECTING, "wake_greeting_refresh")
        updateWakeHandoffUi()
        VoiceFlowLog.warn(
            "wake.greeting.refresh",
            "reason=$reason attempt=$wakeGreetingRefreshAttempts | ${flowContext()}",
        )
        sessionManager.clearUserStandbyDisconnect()
        sessionManager.disconnect()
        sessionManager.ensureConnected()
        return true
    }

    private fun tryCompleteWakeGreetingRefresh() {
        if (!pendingWakeGreetingRefresh) return
        if (!conversationUiActive || !_isConnected.value) {
            VoiceFlowLog.decision("wake.greeting.refresh", "redetect", false, "ui_or_conn")
            return
        }
        if (listenHandoffJob?.isActive == true) return
        pendingWakeGreetingRefresh = false
        pendingAutoStart = false
        isAutoMode = true
        wakeConversationHandoff = true
        _isAwaitingReconnect.value = false
        scheduleWakeGreetingSuppression(preserveProgress = false)
        wakeDetectSentThisRound = false
        ensureDownlinkPlaybackReady(forceReprepare = true)
        if (_state.value == ConversationState.CONNECTING) {
            _state.value = ConversationState.IDLE
        }
        mqttManager.sendWakeWordDetected(WakePhraseMatcher.WAKE_PHRASE)
        wakeDetectSentThisRound = true
        VoiceFlowLog.snapshot("wake.greeting.refresh.detect", flowContext())
        Log.i(TAG, "问候刷新：新会话已重发 detect")
        startAutoConversation()
    }

    /** 结束当前对话轮次，回到待机（不发 listen start） */
    private fun enterStandby(
        reason: String,
        notifyServer: Boolean = false,
        fastWake: Boolean = false,
    ) {
        cancelListenHandoff("enterStandby:$reason")
        cancelSpeakingWatchdog()
        clearAssistantReplyTurn("enterStandby:$reason")
        cancelSessionEndFallback()
        cancelSessionEndStandby()
        stopListeningKeepalive()
        pendingSessionEnd = false
        sessionEndAudioReceived = false
        sessionEndTtsStopSeen = false
        sessionEndReconnectPending = false
        pendingVoiceWake = false
        pendingAutoStart = false
        pendingWakeGreetingRefresh = false
        wakeGreetingFailedThisRound = false
        shouldResumeOnUiReturn = false
        isAutoMode = false
        setWakeGreetingPlaying(false)
        audioManager.stopRecording()
        audioManager.stopPlaying()
        if (notifyServer && _isConnected.value) {
            mqttManager.sendStopListening()
        }
        transitionState(ConversationState.IDLE, "enterStandby:$reason")
        Log.i(TAG, "进入待机 reason=$reason notifyServer=$notifyServer fastWake=$fastWake")
        logFlow("standby.enter", "reason=$reason notifyServer=$notifyServer fastWake=$fastWake")
        if (fastWake) {
            viewModelScope.launch {
                prepareStandbyWakeListening()
            }
        } else {
            resumeWakeListeningIfNeeded()
            updateStandbyReady()
            scheduleStandbyReadyPoll()
        }
    }

    /**
     * 初始化音频服务（在获得权限后调用）
     */
    @SuppressLint("MissingPermission")
    fun initializeAudio() {
        if (shouldDeferMicForWakeListening()) {
            prepareStandbyWakeListening()
            return
        }

        if (isAudioInitialized && audioManager.isReady()) {
            _errorMessage.value = null
            tryHandlePendingVoiceWake()
            if (!pendingVoiceWake) {
                tryHandlePendingRecordKeyStart()
                tryStartAutoConversationIfNeeded()
            }
            return
        }

        if (isAudioInitialized && !audioManager.isReady()) {
            if (audioManager.ensureRecordingReady()) {
                _errorMessage.value = null
                Log.d(TAG, "录音器已重建")
                tryHandlePendingVoiceWake()
                if (!pendingVoiceWake) {
                    tryHandlePendingRecordKeyStart()
                    tryStartAutoConversationIfNeeded()
                }
                return
            }
            isAudioInitialized = false
        }

        if (!audioManager.initialize() || !audioManager.isReady()) {
            isAudioInitialized = false
            _errorMessage.value = if (audioManager.hasRecordPermission()) {
                "录音初始化失败，请重试"
            } else {
                "音频系统初始化失败，请确认已授予麦克风权限"
            }
            return
        }

        isAudioInitialized = true
        _errorMessage.value = null
        Log.d(TAG, "音频系统初始化成功")
        tryHandlePendingVoiceWake()
        if (!pendingVoiceWake) {
            tryHandlePendingRecordKeyStart()
            tryStartAutoConversationIfNeeded()
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureAudioReadyForPendingWake(): Boolean {
        if (isAudioInitialized && audioManager.isReady()) return true

        Log.d(
            TAG,
            "pendingWake: 音频未就绪，尝试重建 init=$isAudioInitialized ready=${audioManager.isReady()}",
        )

        if (!isAudioInitialized) {
            if (!audioManager.initialize() || !audioManager.isReady()) {
                schedulePendingWakeRetry()
                return false
            }
            isAudioInitialized = true
            _errorMessage.value = null
            return true
        }

        if (!audioManager.ensureRecordingReady()) {
            isAudioInitialized = false
            schedulePendingWakeRetry()
            return false
        }
        return true
    }

    private fun schedulePendingWakeRetry() {
        if (!pendingVoiceWake) return
        if (pendingWakeRetryCount >= 8) {
            Log.w(TAG, "pendingWake: 音频重建多次失败，等待 UI 或超时恢复")
            return
        }
        pendingWakeRetryCount++
        pendingWakeRetryJob?.cancel()
        pendingWakeRetryJob = viewModelScope.launch {
            delay(250L * pendingWakeRetryCount)
            if (pendingVoiceWake) {
                Log.d(TAG, "pendingWake: 重试音频就绪检查 #$pendingWakeRetryCount")
                tryHandlePendingVoiceWake()
            }
        }
    }

    /**
     * 尝试开麦：待机 IDLE 仅响应按键/唤醒；已开聊的 auto 会话可续轮。
     */
    private fun tryStartAutoConversationIfNeeded() {
        if (pendingWakeGreetingRefresh) {
            tryCompleteWakeGreetingRefresh()
            return
        }
        if (pendingVoiceWake) {
            tryHandlePendingVoiceWake()
            return
        }
        if (pendingPhotoFromStandby) {
            tryHandlePendingPhotoKey()
            return
        }
        if (pendingRecordKeyStart || XiaozhiAppEvents.hasPendingVoiceKeyPress()) {
            tryHandlePendingRecordKeyStart()
            return
        }
        if (_state.value == ConversationState.IDLE && !pendingAutoStart) {
            Log.d(TAG, "待机态，跳过自动开麦（需录音键或唤醒词）")
            return
        }
        if (!isAutoMode) return
        if (XiaozhiWakeForegroundService.isWakeListeningActive()) {
            Log.d(TAG, "唤醒监听中，跳过自动开麦")
            return
        }
        if (!conversationUiActive || !_isConnected.value) return
        if (_state.value != ConversationState.IDLE) return
        if (!isAudioInitialized || !audioManager.isReady()) {
            pendingAutoStart = true
            return
        }
        pendingAutoStart = false
        startAutoConversation()
    }

    /**
     * 启动事件监听
     */
    private fun startEventListening() {
        // 监听 MQTT 事件 - 确保在 MQTT 连接之前就开始监听
        viewModelScope.launch {
            Log.d(TAG, "开始监听 MQTT + 音频事件")
            mqttManager.events.collect { event ->
                handleMqttUdpEvent(event)
            }
        }

        // 监听音频事件
        viewModelScope.launch {
            audioManager.audioEvents.collect { event ->
                handleAudioEvent(event)
            }
        }
    }

    /** 顺序处理 TTS 文本信令与下行音频，避免音频早于 tts start 被丢弃 */
    private fun startMqttConversationProcessor() {
        viewModelScope.launch(textMessageDispatcher) {
            for (payload in mqttConversationChannel) {
                try {
                    when (payload) {
                        is MqttConversationPayload.Text -> handleTextMessage(payload.message)
                        is MqttConversationPayload.Binary -> handleBinaryMessage(payload.data)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理 WS 对话消息失败", e)
                }
            }
        }
    }

    private fun enqueueMqttConversationPayload(payload: MqttConversationPayload) {
        val result = mqttConversationChannel.trySend(payload)
        if (result.isFailure) {
            Log.w(TAG, "WS 对话消息队列已满，丢弃 type=${payload::class.simpleName}")
        }
    }

    /**
     * 用户确认激活后连接 MQTT
     */
    fun onActivationConfirmed() {
        Log.d(TAG, "用户确认激活，开始连接 MQTT")
        sessionManager.onActivationConfirmed()
    }

    /**
     * 关闭激活弹窗
     */
    fun dismissActivationDialog() {
        sessionManager.dismissActivation()
    }

    /**
     * 连接到服务器（若后台已连接则直接复用）
     */
    fun connect() {
        sessionManager.clearUserStandbyDisconnect()
        sessionManager.reloadConfig()
        config = configManager.loadConfig()
        if (sessionManager.isConnected.value) {
            _isConnected.value = true
            if (_state.value == ConversationState.CONNECTING) {
                _state.value = ConversationState.IDLE
            }
            tryHandlePendingVoiceWake()
            tryHandlePendingRecordKeyStart()
            if (pendingAutoStart && isAutoMode) {
                tryStartAutoConversationIfNeeded()
            }
            return
        }
        _state.value = ConversationState.CONNECTING
        sessionManager.ensureConnected()
    }

    /**
     * 更新配置
     */
    fun needsConfigRefresh(newConfig: XiaozhiConfig): Boolean =
        config.mqtt != newConfig.mqtt ||
            config.otaUrl != newConfig.otaUrl ||
            config.macAddress != newConfig.macAddress ||
            config.token != newConfig.token

    fun updateConfig(newConfig: XiaozhiConfig) {
        config = newConfig
        sessionManager.updateConfig(newConfig)
        Log.d(TAG, "配置已更新")
    }

    /**
     * 说「退下」后等待结束语期间，打印服务器下发的原始数据。
     */
    private fun logSessionEndServerReply(label: String, payload: String) {
        if (!pendingSessionEnd) return
        Log.i(SESSION_END_TAG, "[$label] $payload")
    }

    /**
     * 处理 MQTT 事件
     */
    private fun handleMqttUdpEvent(event: MqttUdpEvent) {
        Log.v(TAG, "WS事件 ${event::class.simpleName} state=${_state.value}")
        when (event) {
            is MqttUdpEvent.HelloReceived -> {
                Log.d(TAG, "握手 HelloReceived")
            }

            is MqttUdpEvent.Connected -> {
                Log.i(TAG, "WS Connected state=${_state.value} ui=$conversationUiActive pendingWake=$pendingVoiceWake")
                logFlow("mqtt.connected", "session=${mqttManager.getSessionId()}")
                _isConnected.value = true
                _isAwaitingReconnect.value = false
                _errorMessage.value = null
                standbyReconnectGraceJob?.cancel()
                _isStandbyReconnecting.value = false
                clearStandbyScreenSleep("ws_connected")
                if (_state.value == ConversationState.CONNECTING) {
                    _state.value = ConversationState.IDLE
                }
                if (_state.value == ConversationState.CONNECTING) {
                    _state.value = ConversationState.IDLE
                }
                tryHandlePendingVoiceWake()
                tryHandlePendingRecordKeyStart()
                tryHandlePendingPhotoKey()
                if (pendingWakeGreetingRefresh) {
                    initializeAudio()
                    tryCompleteWakeGreetingRefresh()
                } else if (pendingAutoStart) {
                    initializeAudio()
                    if (isAutoMode) tryStartAutoConversationIfNeeded()
                }
                if (!XiaozhiWakeForegroundService.isWakeListeningHealthy()) {
                    resumeWakeListeningIfNeeded()
                } else {
                    updateStandbyReady()
                }
            }

            is MqttUdpEvent.Disconnected -> {
                Log.w(TAG, "WS Disconnected state=${_state.value} pendingSessionEnd=$pendingSessionEnd")
                logFlow("mqtt.disconnected", "autoReconnect=${mqttManager.isAutoReconnectEnabled()}")
                if (pendingSessionEnd) {
                    Log.i(
                        SESSION_END_TAG,
                        "[disconnect] MQTT 断开 state=${_state.value}，等待 TTS 播完或超时",
                    )
                }
                _isConnected.value = false
                cancelSpeakingWatchdog()
                stopListeningKeepalive()
                val keepRecordKeyFlow = pendingRecordKeyStart
                audioManager.stopRecording()
                if (!pendingSessionEnd) {
                    audioManager.stopPlaying()
                }
                audioManager.releaseRecorderOnly()
                isAudioInitialized = false
                if (!mqttManager.isAutoReconnectEnabled()) {
                    _isAwaitingReconnect.value = false
                    _isStandbyReconnecting.value = false
                    standbyReconnectGraceJob?.cancel()
                    sessionEndReconnectPending = false
                    when {
                        pendingSessionEnd &&
                            (_state.value == ConversationState.SPEAKING ||
                                _state.value == ConversationState.PROCESSING) -> {
                            Log.i(TAG, "主动断连，等待结束语播完再待机")
                        }
                        _isStandbyScreenSleep.value -> {
                            Log.i(TAG, "待机休眠主动断连，保持唤醒待机")
                            prepareStandbyWakeListening()
                            updateStandbyReady()
                        }
                        else -> {
                            Log.i(TAG, "主动断连 → 唤醒待机，不重连")
                            enterWakeStandbyWithoutReconnect("active_disconnect")
                        }
                    }
                    return
                }
                if (keepRecordKeyFlow) {
                    isAutoMode = true
                    _state.value = ConversationState.CONNECTING
                } else if (pendingSessionEnd &&
                    (_state.value == ConversationState.SPEAKING || _state.value == ConversationState.PROCESSING)
                ) {
                    Log.i(TAG, "服务器断线，等待结束语播完再待机")
                } else if (shouldReconnectAfterConversationDisconnect()) {
                    beginWsReconnectAfterConversationDisconnect()
                } else if (_isStandbyScreenSleep.value) {
                    Log.i(TAG, "待机休眠断连，保持唤醒待机")
                    prepareStandbyWakeListening()
                    updateStandbyReady()
                } else if (_state.value == ConversationState.IDLE) {
                    Log.i(TAG, "服务端空闲断线 → 唤醒待机，不重连")
                    VoiceFlowLog.warn(
                        "mqtt.disconnected",
                        "空闲断线，唤醒待机不重连 | blockers=${standbyReadyBlockers().joinToString(",")}",
                    )
                    enterWakeStandbyWithoutReconnect("idle_disconnect")
                } else {
                    _isAwaitingReconnect.value = false
                    enterStandby("ws_disconnect", notifyServer = false)
                }
            }

            is MqttUdpEvent.TextMessage -> {
                enqueueMqttConversationPayload(MqttConversationPayload.Text(event.message))
            }

            is MqttUdpEvent.BinaryMessage -> {
                enqueueMqttConversationPayload(MqttConversationPayload.Binary(event.data))
            }
            
            is MqttUdpEvent.MCPMessage -> Unit

            is MqttUdpEvent.Error -> {
                Log.e(TAG, "MQTT 错误: ${event.error}")
                logSessionEndServerReply("error", event.error)
                _errorMessage.value = event.error
                _state.value = ConversationState.IDLE
                audioManager.stopRecording()
                audioManager.stopPlaying()
            }
        }
    }

    /**
     * 后台唤醒监听或交接期间，忽略共享 MQTT 会话 上的对话消息。
     */
    private fun shouldIgnoreConversationServerMessages(): Boolean {
        if (XiaozhiAppEvents.isPhotoSessionActive()) {
            return false
        }
        if (isActivelyInConversation()) {
            return false
        }
        if (isWakeGreetingWindow()) {
            return false
        }
        if (XiaozhiWakeCoordinator.hasServerGreetingTtsPending() &&
            XiaozhiWakeCoordinator.isWakeHandoffInProgress()
        ) {
            return false
        }
        return pendingVoiceWake ||
            XiaozhiWakeCoordinator.isWakeHandoffInProgress() ||
            XiaozhiWakeForegroundService.isWakeListeningActive() ||
            listenHandoffJob?.isActive == true
    }

    /**
     * 处理文本消息
     */
    private fun handleTextMessage(message: String) {
        try {
            if (shouldIgnoreConversationServerMessages()) {
                VoiceFlowLog.step(
                    "msg.ignore",
                    "wakeActive=${XiaozhiWakeForegroundService.isWakeListeningActive()} | ${flowContext()}",
                )
                Log.v(TAG, "忽略唤醒阶段消息")
                return
            }
            if (pendingSessionEnd) {
                logSessionEndServerReply("text", message)
            }
            val json = gson.fromJson(message, JsonObject::class.java)
            val type = json.get("type")?.asString
            if (type == "mcp") return
            val sessionId = json.get("session_id")?.asString

            if (type == "tts") {
                val ttsState = json.get("state")?.asString
                Log.d(TAG, "消息 type=$type state=$ttsState session=$sessionId ui=${_state.value}")
            } else {
                Log.d(TAG, "消息 type=$type session=$sessionId state=${_state.value}")
            }

            when (type) {
                "stt" -> {
                    if (!conversationUiActive) return@handleTextMessage
                    val text = json.get("text")?.asString
                    if (hideNextSttEcho) {
                        hideNextSttEcho = false
                        Log.i(PhotoKeyLog.TAG, "隐藏短指令 STT 回显: $text")
                        if (!text.isNullOrEmpty()) {
                            onPhotoServerSignal(text, "stt_echo")
                        }
                        return@handleTextMessage
                    }
                    if (shouldSuppressWakeSttEcho(text)) {
                        VoiceFlowLog.decision("msg.stt", "处理", false, "唤醒回显")
                        Log.d(TAG, "唤醒交接中，忽略 STT: $text")
                        return@handleTextMessage
                    }
                    Log.i(TAG, "STT: $text sessionEnd=${!text.isNullOrEmpty() && WakePhraseMatcher.isSessionEndPhrase(text)}")
                    if (!text.isNullOrEmpty() && XiaozhiAppEvents.isPhotoSessionActive()) {
                        if (isLikelyVisionJsonEcho(text)) {
                            Log.d(PhotoKeyLog.TAG, "拍照会话中忽略视觉 JSON 回显")
                            return@handleTextMessage
                        }
                        if (onPhotoServerSignal(text, "stt")) {
                            Log.d(PhotoKeyLog.TAG, "拍照会话 STT 已处理: $text")
                            return@handleTextMessage
                        }
                        Log.d(PhotoKeyLog.TAG, "拍照会话中忽略 STT 回显")
                        return@handleTextMessage
                    }
                    if (!text.isNullOrEmpty() && WakePhraseMatcher.matches(text)) {
                        Log.d(TAG, "忽略唤醒词 STT")
                        return@handleTextMessage
                    }
                    if (!text.isNullOrEmpty() && WakePhraseMatcher.isSessionEndPhrase(text)) {
                        if (!wakeGreetingPhaseComplete &&
                            _isWakeGreetingPlaying.value &&
                            audioManager.isPlaying()
                        ) {
                            Log.d(TAG, "问候播报中忽略 STT 回显: $text")
                            return@handleTextMessage
                        }
                        Log.i(TAG, "检测到结束对话语句: $text，等待服务器回复")
                        logFlow("sessionEnd.detected", "text=$text")
                        Log.i(
                            SESSION_END_TAG,
                            "用户说「$text」，开始等待服务器结束语（state=${_state.value}）",
                        )
                        currentUserMessage = text
                        addMessage(Message(role = MessageRole.USER, content = text))
                        audioManager.stopRecording()
                        pendingSessionEnd = true
                        sessionEndAudioReceived = false
                        sessionEndTtsStopSeen = false
                        isAutoMode = false
                        transitionState(ConversationState.PROCESSING, "session_end_stt")
                        beginSessionEndWindDown()
                        scheduleSessionEndFallback()
                        return@handleTextMessage
                    }
                    // 单字+标点噪声（嗯。/啊！）不送对话；仅「好」放行。多字/退下/拍照不受影响
                    if (!text.isNullOrEmpty() && shouldIgnoreSingleCharSymbolStt(text)) {
                        Log.i(TAG, "忽略单字符号 STT（保持聆听）: $text")
                        VoiceFlowLog.decision("msg.stt", "处理", false, "单字符号噪声 text=$text")
                        if (_state.value == ConversationState.LISTENING && isAutoMode) {
                            abortPostWakeSpuriousTts("stt_noise")
                        }
                        return@handleTextMessage
                    }
                    if (!text.isNullOrEmpty() && !text.contains("请登录控制面板")) {
                        clearPostWakeSpuriousTtsSuppress("real_user_stt")
                        clearWakeGreetingSuppression()
                        currentUserMessage = text
                        addMessage(Message(
                            role = MessageRole.USER,
                            content = text
                        ))
                        audioManager.stopRecording()
                        transitionState(ConversationState.PROCESSING, "stt")
                    }
                }
                
                "llm" -> {
                    if (shouldSuppressWakeHandoffEcho() && !isWakeGreetingWindow()) {
                        VoiceFlowLog.decision("msg.llm", "处理", false, "handoff中")
                        Log.d(TAG, "唤醒交接中，忽略 LLM")
                        return@handleTextMessage
                    }
                    if (shouldSuppressPostWakeSpuriousTts() && !isWakeGreetingTurn()) {
                        return@handleTextMessage
                    }
                    val emotion = json.get("emotion")?.asString
                    val text = json.get("text")?.asString
                    if (text.isNullOrBlank() || isLikelyEmotionOnly(text)) {
                        Log.d(TAG, "LLM emotion=$emotion（纯表情，不写入聊天气泡）")
                        return@handleTextMessage
                    }
                    Log.d(TAG, "LLM emotion=$emotion text=$text")
                    if (shouldApplyServerAssistantText(text)) {
                        updateAssistantMessage(text)
                    }
                    armSessionEndFromAssistantFarewell(text, "llm")
                }
                
                "tts" -> {
                    val ttsState = json.get("state")?.asString
                    if (shouldSuppressWakeHandoffEcho() && ttsState == "stop" && !isWakeGreetingWindow()) {
                        VoiceFlowLog.decision("msg.tts.stop", "处理", false, "handoff中")
                        Log.d(TAG, "唤醒交接中，忽略 TTS stop")
                        return@handleTextMessage
                    }
                    val state = ttsState
                    when (state) {
                        "sentence_start" -> {
                            // 结束语播放：设备此刻在出声，切到 SPEAKING 让 UI 显示「说话中」而非「思考中」
                            if (pendingSessionEnd && _state.value == ConversationState.PROCESSING) {
                                transitionState(ConversationState.SPEAKING, "session_end_farewell")
                            }
                            // TTS句子开始，显示要播放的文本
                            val text = json.get("text")?.asString
                            if (!text.isNullOrEmpty() && handlePhotoServerErrorFromTts(text, "sentence_start")) {
                                return@handleTextMessage
                            }
                            if (!text.isNullOrEmpty() && onPhotoServerSignal(text, "tts_sentence_start")) {
                                return@handleTextMessage
                            }
                            if (!text.isNullOrEmpty() && shouldApplyServerAssistantText(text)) {
                                updateAssistantMessage(text)
                            }
                            if (!text.isNullOrEmpty()) {
                                armSessionEndFromAssistantFarewell(text, "tts_sentence_start")
                            }
                        }
                        "sentence_end" -> {
                            val text = json.get("text")?.asString
                            if (!text.isNullOrEmpty() && handlePhotoServerErrorFromTts(text, "sentence_end")) {
                                return@handleTextMessage
                            }
                            if (!text.isNullOrEmpty() && onPhotoServerSignal(text, "tts_sentence_end")) {
                                return@handleTextMessage
                            }
                            if (shouldIgnoreStaleReplyWhileListening()) {
                                Log.d(TAG, "聆听中忽略迟来 TTS sentence_end")
                                return@handleTextMessage
                            }
                            // TTS句子结束，有时包含完整的句子内容
                            if (!text.isNullOrEmpty() && shouldApplyServerAssistantText(text)) {
                                // 检查是否需要更新（如果sentence_start已经包含了这部分内容则跳过，或者直接替换为更完整的text）
                                // 这里简单处理：如果当前最后一条助手消息内容不包含这段text，则更新/追加
                                Log.d(TAG, "TTS sentence_end: $text")
                                // 注意：根据不同服务端的实现，sentence_end 可能包含整句，也可能只是最后一段
                                // 为了保险，这里我们信任 sentence_end 的完整性，如果它比当前存的长，就用它
                                syncAssistantMessage(text)
                            }
                            // 结束语场景：部分服务端只发 sentence_end、不发 tts stop。
                            // 收到结束语句尾即按"播完"处理，等音频排空后断开进待机，
                            // 避免死等永不到来的 tts stop 撑满 20s 超时。
                            if (pendingSessionEnd && !sessionEndTtsStopSeen) {
                                sessionEndTtsStopSeen = true
                                cancelSpeakingWatchdog()
                                Log.i(SESSION_END_TAG, "结束语 sentence_end → 等播完即断开（无 tts stop）")
                                logFlow("sessionEnd.sentenceEnd", "text=$text")
                                scheduleSessionEndCompletion("session_end_sentence_end")
                            }
                        }
                        "start" -> {
                            if (!conversationUiActive) return@handleTextMessage
                            if (handlePhotoFailureTtsSuppress("start")) {
                                return@handleTextMessage
                            }
                            if (shouldSuppressPhotoTtsPlayback("tts_start")) {
                                val alreadyPlayingResult =
                                    photoResultTtsAllowed && photoResultTtsStarted
                                Log.i(
                                    PhotoKeyLog.TAG,
                                    "丢弃拍照 TTS start state=${_state.value} " +
                                        "allowResult=$photoResultTtsAllowed started=$photoResultTtsStarted " +
                                        "keepPlaying=$alreadyPlayingResult",
                                )
                                // 已在播结果时勿 stopPlaying，否则会打断正常播报
                                if (!alreadyPlayingResult) {
                                    audioManager.stopPlaying()
                                }
                                return@handleTextMessage
                            }
                            if (photoResultTtsAllowed && !photoResultTtsStarted) {
                                photoResultTtsStarted = true
                                audioManager.stopRecording()
                                transitionState(ConversationState.SPEAKING, "photo_result_tts")
                                scheduleSpeakingWatchdog()
                                Log.i(PhotoKeyLog.TAG, "放行拍照结果 TTS start → SPEAKING")
                                return@handleTextMessage
                            }
                            if (shouldArmWakeGreetingFromServerTts()) {
                                armWakeGreetingFromServerTtsIfNeeded()
                                markWakeGreetingTtsStart()
                                Log.d(TAG, "WakeSTT 路径问候 TTS start（唤醒交接中）")
                                return@handleTextMessage
                            }
                            if (listenHandoffJob?.isActive == true && !isWakeGreetingWindow()) {
                                VoiceFlowLog.decision("msg.tts.start", "→SPEAKING", false, "开麦交接中")
                                Log.d(TAG, "开麦交接中，忽略 TTS start")
                                return@handleTextMessage
                            }
                            if (_state.value == ConversationState.LISTENING &&
                                isAutoMode &&
                                audioManager.isRecording()
                            ) {
                                if (isWakeGreetingTurn()) {
                                    markWakeGreetingTtsStart()
                                    Log.d(TAG, "唤醒问候播放中，保持聆听")
                                    return@handleTextMessage
                                }
                                if (XiaozhiAppEvents.isPhotoSessionActive() &&
                                    !photoAwaitingMcpCapture
                                ) {
                                    audioManager.stopRecording()
                                    transitionState(ConversationState.SPEAKING, "photo_tts_start")
                                    scheduleSpeakingWatchdog()
                                    VoiceFlowLog.decision(
                                        "msg.tts.start",
                                        "→SPEAKING",
                                        true,
                                        "拍照结果播报",
                                    )
                                    Log.i(PhotoKeyLog.TAG, "拍照结果 TTS start → SPEAKING")
                                    return@handleTextMessage
                                }
                                // 长回答多句：中间曾误回聆听时，续句应继续 SPEAKING，勿 abort
                                if (assistantReplyActive || audioManager.isPlaying()) {
                                    markAssistantReplyActive("tts_start_resume")
                                    audioManager.stopRecording()
                                    transitionState(ConversationState.SPEAKING, "assistant_continue_tts")
                                    scheduleSpeakingWatchdog()
                                    Log.i(TAG, "助手续句 TTS start → 恢复说话中")
                                    return@handleTextMessage
                                }
                                VoiceFlowLog.decision(
                                    "msg.tts.start",
                                    "→SPEAKING",
                                    false,
                                    "已在聆听，忽略迟来问候",
                                )
                                Log.i(TAG, "已在聆听，忽略迟来 TTS start（并打断多余播报）")
                                abortPostWakeSpuriousTts("late_wake_greeting")
                                return@handleTextMessage
                            }
                            if (listenHandoffJob?.isActive == true && isWakeGreetingTurn()) {
                                markWakeGreetingTtsStart()
                                Log.d(TAG, "唤醒问候 TTS start（handoff 等待 SPEAKING 播放）")
                                return@handleTextMessage
                            }
                            val canPlayTts = _state.value == ConversationState.PROCESSING ||
                                _state.value == ConversationState.SPEAKING ||
                                pendingSessionEnd ||
                                (_state.value == ConversationState.LISTENING && isAutoMode) ||
                                (isAutoMode && shouldSuppressWakeHandoffEcho())
                            if (!canPlayTts) {
                                if (pendingVoiceWake ||
                                    isWakeGreetingWindow() ||
                                    wakeConversationHandoff ||
                                    isWakeHandoffInProgress()
                                ) {
                                    markWakeGreetingTtsStart()
                                    Log.d(TAG, "唤醒阶段 IDLE TTS start，准备播放问候")
                                    return@handleTextMessage
                                }
                                VoiceFlowLog.decision(
                                    "msg.tts.start",
                                    "→SPEAKING",
                                    false,
                                    "state=${_state.value} auto=$isAutoMode pendingEnd=$pendingSessionEnd",
                                )
                                Log.d(TAG, "忽略非对话中的 TTS start")
                                return@handleTextMessage
                            }
                            markAssistantReplyActive("tts_start")
                            transitionState(ConversationState.SPEAKING, "tts_start")
                            scheduleSpeakingWatchdog()
                            VoiceFlowLog.decision("msg.tts.start", "→SPEAKING", true, flowContext())
                            Log.d(TAG, "TTS start → SPEAKING")
                        }
                        "stop" -> {
                            if (shouldIgnoreStalePhotoTtsControl()) {
                                Log.i(
                                    PhotoKeyLog.TAG,
                                    "忽略上一轮迟来 TTS stop（等待 MCP capture） state=${_state.value}",
                                )
                                audioManager.stopPlaying()
                                return@handleTextMessage
                            }
                            if (isPhotoFailureTtsSuppressActive()) {
                                handlePhotoFailureTtsSuppress("stop")
                                return@handleTextMessage
                            }
                            if (_state.value == ConversationState.SPEAKING &&
                                isWakeGreetingTurn() &&
                                !pendingSessionEnd
                            ) {
                                if (!shouldAcceptWakeGreetingTtsStop()) {
                                    // 记 stop，等 Opus 播完；勿 complete（否则无声就开麦）
                                    wakeGreetingTtsStopSeen = true
                                    Log.i(TAG, "问候 TTS stop 早到（尚无 Opus），继续等待播报")
                                    return@handleTextMessage
                                }
                                wakeGreetingTtsStopSeen = true
                                setWakeGreetingPlaying(false)
                                mqttManager.stopWakeGreetingNatKeepalive("greeting_tts_stop")
                                completeWakeGreetingPhase("speaking_tts_stop")
                                cancelSpeakingWatchdog()
                                if (listenHandoffJob?.isActive == true) {
                                    transitionState(ConversationState.IDLE, "wake_greeting_handoff_wait")
                                    Log.d(TAG, "问候 TTS stop（handoff 等待开麦）")
                                    return@handleTextMessage
                                }
                            }
                            if (_state.value == ConversationState.IDLE &&
                                (isWakeGreetingWindow() || isWakeHandoffInProgress())
                            ) {
                                if (shouldAcceptWakeGreetingTtsStop()) {
                                    wakeGreetingTtsStopSeen = true
                                    setWakeGreetingPlaying(false)
                                    mqttManager.stopWakeGreetingNatKeepalive("idle_tts_stop")
                                    completeWakeGreetingPhase("idle_tts_stop")
                                    Log.d(TAG, "唤醒交接/问候窗口内 IDLE TTS stop")
                                } else {
                                    wakeGreetingTtsStopSeen = true
                                    Log.i(TAG, "唤醒交接 IDLE TTS stop 早到（尚无 Opus），继续等待")
                                }
                                return@handleTextMessage
                            }
                            if (_state.value == ConversationState.LISTENING &&
                                isAutoMode &&
                                !pendingSessionEnd &&
                                isWakeGreetingTurn()
                            ) {
                                if (!shouldAcceptWakeGreetingTtsStop()) {
                                    Log.d(TAG, "忽略未开始播放的迟来问候 TTS stop")
                                    return@handleTextMessage
                                }
                                wakeGreetingTtsStopSeen = true
                                setWakeGreetingPlaying(false)
                                wakeGreetingAudioReceived = false
                                completeWakeGreetingPhase("listening_tts_stop")
                                Log.d(TAG, "唤醒问候 TTS stop，继续聆听")
                                return@handleTextMessage
                            }
                            if (shouldIgnoreStaleReplyWhileListening()) {
                                if (_isWakeGreetingPlaying.value) {
                                    setWakeGreetingPlaying(false)
                                }
                                VoiceFlowLog.decision(
                                    "msg.tts.stop",
                                    "finishSpeakingTurn",
                                    false,
                                    "聆听中忽略迟来 TTS stop",
                                )
                                Log.d(TAG, "聆听中忽略迟来 TTS stop")
                                return@handleTextMessage
                            }
                            // 普通长回答：句尾 stop 先排空，勿立刻 stopPlaying + 回聆听
                            if (shouldDeferAssistantReplyFinish()) {
                                assistantReplyTtsStopSeen = true
                                cancelSpeakingWatchdog()
                                VoiceFlowLog.step(
                                    "msg.tts.stop",
                                    "defer drain play=${audioManager.isPlaying()}",
                                )
                                Log.i(
                                    TAG,
                                    "助手回答 TTS stop → 等播完再回聆听 " +
                                        "play=${audioManager.isPlaying()}",
                                )
                                scheduleAssistantReplyDrain("tts_stop")
                                return@handleTextMessage
                            }
                            if (!pendingSessionEnd) {
                                audioManager.stopPlaying()
                            }
                            VoiceFlowLog.step("msg.tts.stop", "pendingEnd=$pendingSessionEnd play=${audioManager.isPlaying()}")
                            Log.d(TAG, "TTS stop pendingSessionEnd=$pendingSessionEnd")
                            if (!conversationUiActive) {
                                if (isAutoMode) shouldResumeOnUiReturn = true
                                cancelSpeakingWatchdog()
                                return@handleTextMessage
                            }
                            finishSpeakingTurn("tts_stop")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析文本消息失败", e)
            VoiceFlowLog.error("msg.parse", e.message ?: "unknown")
        }
    }

    /** 音频帧可能早于 tts start 到达，提前切换到可播放状态 */
    private fun prepareForDownlinkAudio() {
        if (shouldSuppressPostWakeSpuriousTts() && !isWakeGreetingTurn()) {
            return
        }
        when (_state.value) {
            ConversationState.LISTENING -> {
                if (isAutoMode && !isWakeGreetingTurn()) {
                    markAssistantReplyActive("binary_leads")
                    audioManager.stopRecording()
                    transitionState(ConversationState.SPEAKING, "binary_leads_tts")
                    scheduleSpeakingWatchdog()
                }
            }
            ConversationState.PROCESSING -> {
                if (!pendingSessionEnd) {
                    markAssistantReplyActive("binary_leads")
                    transitionState(ConversationState.SPEAKING, "binary_leads_tts")
                    scheduleSpeakingWatchdog()
                }
            }
            ConversationState.SPEAKING -> {
                // 长回答持续下行：刷新看门狗，避免绝对超时误杀
                if (assistantReplyActive && speakingWatchdogJob?.isActive != true &&
                    assistantReplyDrainJob?.isActive != true
                ) {
                    scheduleSpeakingWatchdog()
                }
            }
            else -> Unit
        }
    }

    /**
     * 处理二进制消息（音频数据）
     */
    private fun handleBinaryMessage(data: ByteArray) {
        if (shouldArmWakeGreetingFromServerTts() && conversationUiActive) {
            armWakeGreetingFromServerTtsIfNeeded()
            if (!wakeGreetingTtsStartSeen) {
                markWakeGreetingTtsStart()
            }
        }
        if (!conversationUiActive || shouldIgnoreConversationServerMessages()) return
        // 须在 prepareForDownlinkAudio 之前：避免中间 TTS 把状态推成 SPEAKING
        if (shouldSuppressPhotoTtsPlayback("binary")) {
            Log.v(PhotoKeyLog.TAG, "丢弃拍照屏蔽期下行音频 ${data.size}B")
            return
        }
        if (shouldSuppressPostWakeSpuriousTts() && !isWakeGreetingTurn()) {
            Log.d(TAG, "丢弃开麦后多余 TTS 音频 ${data.size}B")
            return
        }
        prepareForDownlinkAudio()
        if (!shouldPlayDownlinkAudio()) {
            VoiceFlowLog.step(
                "msg.binary",
                "忽略待机态TTS音频 ${data.size}B state=${_state.value}",
            )
            Log.v(TAG, "忽略待机态 TTS 音频 ${data.size} bytes state=${_state.value}")
            return
        }
        if (pendingSessionEnd) {
            sessionEndAudioReceived = true
            logSessionEndServerReply("binary", "tts_audio ${data.size} bytes")
        }
        if (isWakeGreetingTurn() && _state.value != ConversationState.CONNECTING) {
            val first = !wakeGreetingAudioReceived
            wakeGreetingAudioReceived = true
            setWakeGreetingPlaying(true)
            if (first) {
                mqttManager.stopWakeGreetingNatKeepalive("first_greeting_opus")
                Log.i(TAG, "首帧问候 Opus ${data.size}B state=${_state.value}")
            }
        }
        if (assistantReplyActive || _state.value == ConversationState.SPEAKING) {
            assistantReplyAudioReceived = true
        }
        ensureDownlinkPlaybackReady()
        Log.d(TAG, "收到音频 ${data.size} bytes state=${_state.value}")
        audioManager.playAudio(data)
    }

    /**
     * 处理音频事件
     */
    private fun handleAudioEvent(event: AudioEvent) {
        when (event) {
            is AudioEvent.AudioData -> {
                if (!shouldSendUplinkAudio()) return
                lastUplinkAudioAtMs = System.currentTimeMillis()
                if (!hasLoggedFirstAudioFrame) {
                    hasLoggedFirstAudioFrame = true
                    Log.d(TAG, "首帧音频上行 ${event.data.size}B")
                    VoiceFlowLog.step("listen.uplink", "首帧已上行 ${event.data.size}B")
                }
                mqttManager.sendBinaryMessage(event.data)
            }
            is AudioEvent.Error -> {
                Log.e(TAG, "音频错误: ${event.message}")
                _errorMessage.value = event.message
                stopListening()
            }
        }
    }

    /**
     * 开始聆听（手动模式）
     */
    @SuppressLint("MissingPermission")
    fun startListening() {
        Log.d(TAG, "手动开麦已禁用，请使用录音键或唤醒词")
    }

    /**
     * 开始自动对话模式
     */
    @SuppressLint("MissingPermission")
    private fun startAutoConversation(): Boolean {
        if (!conversationUiActive) {
            pendingAutoStart = true
            return false
        }
        if (_state.value == ConversationState.LISTENING && isAutoMode && audioManager.isRecording()) {
            // 本地已在听，但服务端 listen 可能已超时（偶发「聆听中却不识别」）→ 只续期
            renewListenSession("already_listening_refresh")
            startListeningKeepalive()
            Log.d(TAG, "已在自动聆听，刷新服务端 listen")
            return true
        }
        if (_state.value != ConversationState.IDLE || !_isConnected.value) {
            Log.w(TAG, "无法开麦 state=${_state.value} connected=${_isConnected.value}")
            return false
        }
        if (!audioManager.hasRecordPermission()) {
            _errorMessage.value = "请先授予麦克风权限"
            return false
        }
        if (listenHandoffJob?.isActive == true) {
            Log.d(TAG, "开麦交接进行中，跳过")
            return true
        }

        listenHandoffJob = viewModelScope.launch {
            try {
                performAutoConversationHandoff()
            } finally {
                // finally 期间 Job.isActive 仍为 true，若不清引用会导致 handoffUi 卡死为 true
                listenHandoffJob = null
                updateWakeHandoffUi()
            }
        }
        updateWakeHandoffUi()
        VoiceFlowLog.step("handoff.start", "协程已启动 | ${flowContext()}")
        return true
    }

    /**
     * 从唤醒/待机切到对话聆听：先停服务端 listen，再开麦，最后再 listen start。
     * 避免「已在录音但服务端仍是 stop / 唤醒 listen」导致 UI 假聆听。
     */
    @SuppressLint("MissingPermission")
    private suspend fun performAutoConversationHandoff() {
        val t0 = System.currentTimeMillis()
        updateWakeHandoffUi()
        VoiceFlowLog.snapshot("handoff.begin", flowContext())
        XiaozhiWakeForegroundService.claimMicrophoneForConversationAwait(getApplication())
        XiaozhiWakeCoordinator.refreshHandoffTimeout(getApplication())
        pauseWakeListening()

        isAutoMode = true
        hasLoggedFirstAudioFrame = false
        if (isWakeGreetingWindow()) {
            Log.i(TAG, "唤醒问候：官方流程 SPEAKING 播完再 listen/start + 开麦")
            VoiceFlowLog.step("handoff", "await wake greeting SPEAKING")
            // detect 常在 handoff 前已发送；不可因 wakeDetectSentThisRound 而跳过延长等待，
            // 否则偶发只展示「你好智询」、问候 TTS 未到就假开麦
            var greetingStarted = awaitWakeGreetingTtsStart(timeoutMs = 2_500L)
            if (!greetingStarted &&
                !wakeGreetingTtsStartSeen &&
                !_isWakeGreetingPlaying.value
            ) {
                if (!wakeDetectSentThisRound) {
                    mqttManager.sendWakeWordDetected(WakePhraseMatcher.WAKE_PHRASE)
                    wakeDetectSentThisRound = true
                    Log.w(TAG, "未收到问候 TTS start，fallback 发送 detect")
                    VoiceFlowLog.warn("wake.detect.fallback", "no_server_greeting_tts")
                } else {
                    VoiceFlowLog.warn("wake.greeting.wait", "detect_already_sent_extend_wait")
                }
                greetingStarted = awaitWakeGreetingTtsStart(timeoutMs = 5_000L)
                if (!greetingStarted &&
                    !wakeGreetingTtsStartSeen &&
                    !_isWakeGreetingPlaying.value
                ) {
                    mqttManager.sendWakeWordDetected(WakePhraseMatcher.WAKE_PHRASE)
                    wakeDetectSentThisRound = true
                    Log.w(TAG, "仍无问候 TTS，重发 detect")
                    VoiceFlowLog.warn("wake.detect.retry", "still_no_greeting_tts")
                    awaitWakeGreetingTtsStart(timeoutMs = 4_000L)
                }
            }
            if (wakeGreetingTtsStartSeen || _isWakeGreetingPlaying.value || wakeGreetingAudioReceived) {
                awaitWakeGreetingTtsEnd()
                wakeGreetingRefreshAttempts = 0
                wakeGreetingFailedThisRound = false
            } else {
                wakeGreetingFailedThisRound = true
                VoiceFlowLog.warn("wake.greeting.missing", "no_tts | ${flowContext()}")
                // 假连接上 detect 无效时常见：勿裸开麦，换会话再 detect
                if (requestWakeGreetingSessionRefresh("no_greeting_tts")) {
                    return
                }
                VoiceFlowLog.warn("wake.greeting.missing", "open_mic_without_greeting | ${flowContext()}")
            }
            cancelSpeakingWatchdog()
            audioManager.stopPlaying()
            if (_state.value == ConversationState.SPEAKING) {
                transitionState(ConversationState.IDLE, "wake_greeting_handoff")
            }
            mqttManager.sendStopListening()
            VoiceFlowLog.step("handoff", "greeting done sendStopListening")
            delay(80)
        } else {
            mqttManager.sendStopListening()
            VoiceFlowLog.step("handoff", "sendStopListening + delay 80ms")
            delay(80)
        }

        if (_state.value == ConversationState.SPEAKING) {
            VoiceFlowLog.warn("handoff", "SPEAKING 冲突（问候回显），abort 后继续开麦")
            mqttManager.sendAbort("wake_greeting_echo")
            audioManager.stopPlaying()
            transitionState(ConversationState.IDLE, "handoff_clear_speaking")
            delay(80)
        }

        if (!isAutoMode || _state.value != ConversationState.IDLE) {
            VoiceFlowLog.warn("handoff.abort", "cancelled state=${_state.value} auto=$isAutoMode")
            Log.d(TAG, "开麦交接已取消 state=${_state.value}")
            isAutoMode = false
            clearWakeConversationHandoff("handoff_cancelled")
            XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
            return
        }
        if (!_isConnected.value || !conversationUiActive) {
            VoiceFlowLog.warn(
                "handoff.abort",
                "disconnected=${!_isConnected.value} uiInactive=${!conversationUiActive}",
            )
            isAutoMode = false
            clearWakeConversationHandoff("handoff_disconnected")
            XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
            return
        }
        if (!ensureRecordingReady()) {
            VoiceFlowLog.warn("handoff.abort", "音频未就绪")
            isAutoMode = false
            pendingAutoStart = true
            clearWakeConversationHandoff("handoff_audio_not_ready")
            XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
            if (pendingRecordKeyStart || XiaozhiAppEvents.hasPendingVoiceKeyPress()) {
                scheduleRecordKeyRetry()
            }
            return
        }

        if (!audioManager.startRecording()) {
            VoiceFlowLog.error("handoff.abort", "startRecording 失败")
            isAutoMode = false
            _state.value = ConversationState.IDLE
            Log.e(TAG, "startRecording 失败")
            clearWakeConversationHandoff("handoff_record_failed")
            XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
            if (pendingRecordKeyStart || XiaozhiAppEvents.hasPendingVoiceKeyPress()) {
                scheduleRecordKeyRetry()
            }
            return
        }

        mqttManager.sendStartListening("auto")
        wakeGreetingListenActive = false
        wakeDetectSentThisRound = false
        // 真正开麦后结束问候阶段，避免 isWakeGreetingTurn 长期为 true 卡住后续逻辑
        completeWakeGreetingPhase("listening_started")
        mqttManager.stopWakeGreetingNatKeepalive("listening_started")
        armPostWakeSpuriousTtsSuppress("listening_started", durationMs = 3_500L)
        transitionState(ConversationState.LISTENING, "handoff_done")
        pendingAutoStart = false
        pendingRecordKeyStart = false
        pendingRecordKeyRetryCount = 0
        pendingRecordKeyRetryJob?.cancel()
        XiaozhiAppEvents.clearPendingVoiceKeyPress()
        scheduleListeningHealthCheck()
        updateStandbyReady()
        clearWakeConversationHandoff("listening_started")
        val elapsed = System.currentTimeMillis() - t0
        VoiceFlowLog.snapshot(
            "handoff.done",
            "elapsed=${elapsed}ms recording=${audioManager.isRecording()} | ${flowContext()}",
        )
        Log.i(TAG, "开始自动对话 mode=auto recording=${audioManager.isRecording()}")
    }

    private fun cancelListenHandoff(reason: String = "handoff_cancelled") {
        val hadJob = listenHandoffJob != null
        val claimed = XiaozhiWakeForegroundService.isConversationMicClaimed()
        listenHandoffJob?.cancel()
        listenHandoffJob = null
        clearWakeConversationHandoff(reason)
        XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
        updateWakeHandoffUi()
        if (hadJob || claimed) {
            VoiceFlowLog.snapshot(
                "handoff.cancel",
                "reason=$reason hadJob=$hadJob claimed=$claimed | ${flowContext()}",
            )
        }
    }

    /** 若 UI 为聆听中但麦克风未工作，回退待机避免假状态 */
    private fun scheduleListeningHealthCheck() {
        viewModelScope.launch {
            delay(400)
            if (_state.value == ConversationState.LISTENING && !audioManager.isRecording()) {
                Log.w(TAG, "聆听状态异常：麦克风未录音 → 待机")
                isAutoMode = false
                mqttManager.sendStopListening()
                transitionState(ConversationState.IDLE, "listen_health_mic_dead")
                XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
                prepareStandbyWakeListening()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureRecordingReady(): Boolean {
        if (!audioManager.hasRecordPermission()) {
            _errorMessage.value = "请先授予麦克风权限"
            return false
        }
        if (isAudioInitialized && audioManager.isReady()) {
            _errorMessage.value = null
            return true
        }
        if (!isAudioInitialized) {
            if (!audioManager.initialize() || !audioManager.isReady()) {
                isAudioInitialized = false
                _errorMessage.value = "录音初始化失败，请重试"
                Log.w(TAG, "ensureRecordingReady: 麦克风流初始化失败")
                return false
            }
            isAudioInitialized = true
            _errorMessage.value = null
            return true
        }
        if (!audioManager.ensureRecordingReady()) {
            isAudioInitialized = false
            _errorMessage.value = "录音初始化失败，请重试"
            return false
        }
        return true
    }

    /**
     * 停止聆听
     */
    fun stopListening() {
        if (_state.value != ConversationState.LISTENING) {
            return
        }

        audioManager.stopRecording()
        _state.value = ConversationState.PROCESSING
        
        // 发送停止聆听消息
        mqttManager.sendStopListening()
        Log.d(TAG, "停止聆听")
    }

    /**
     * 取消当前录音并发送中止信号（可选原因）
     * 用于上滑取消等场景：立即停止录音、停止Opus数据传输，并发送 type=abort 给服务器。
     */
    fun cancelListeningWithAbort(reason: String = "user_interrupt") {
        // 将状态置为IDLE，确保 handleAudioEvent 不再发送后续音频帧
        if (_state.value == ConversationState.LISTENING) {
            _state.value = ConversationState.IDLE
        }
        // 停止录音，确保底层不再采集与编码音频
        audioManager.stopRecording()

        // 发送中止信号到服务器，包含 session_id 与原因
        mqttManager.sendAbort(reason)

        Log.d(TAG, "取消录音 abort=$reason")
    }

    /**
     * 发送文本消息
     */
    fun sendTextMessage(text: String) {
        if (!_isConnected.value || text.isBlank()) {
            return
        }
        // 发送唤醒词检测消息
        mqttManager.sendTextRequest(text)
        _state.value = ConversationState.PROCESSING
        Log.d(TAG, "发送文本: $text")
    }

    /**
     * 发送初始化消息（设备激活时使用，不添加到对话列表）
     */
    private fun sendInitializationMessage() {
        // 发送"初始化"文本消息，但不添加到对话列表
        mqttManager.sendTextRequest("初始化")
        Log.d(TAG, "发送设备初始化消息")
    }

    /**
     * 打断当前对话
     */
    fun interrupt() {
        if (_isConnected.value) {
            mqttManager.sendAbort("user_interrupt")
        }
        enterStandby("user_interrupt", notifyServer = false)
        Log.i(TAG, "用户打断对话")
    }

    /**
     * 断开连接并停止所有操作
     */
    fun disconnect() {
        Log.i(TAG, "用户主动断开连接")
        sessionManager.disconnectForUserStandby()
        enterStandby("user_disconnect", notifyServer = false)
    }

    /**
     * 更新助手消息（用于流式输出）
     */
    private fun shouldStartNewAssistantBubble(): Boolean {
        if (forceNextAssistantBubble) return true
        if (pendingSessionEnd) return true
        val msgs = _messages.value
        if (msgs.isEmpty()) return true
        if (msgs.last().role != MessageRole.ASSISTANT) return true
        val lastUser = msgs.lastOrNull { it.role == MessageRole.USER }
        if (lastUser != null && WakePhraseMatcher.isSessionEndPhrase(lastUser.content)) return true
        val lastAssistantIdx = msgs.indexOfLast { it.role == MessageRole.ASSISTANT }
        return msgs.subList(lastAssistantIdx + 1, msgs.size).any { it.role == MessageRole.USER }
    }

    private fun updateAssistantMessage(text: String) {
        if (shouldUsePhotoRoundAssistantBubble()) {
            updatePhotoRoundAssistantText(text, preferReplace = false)
            return
        }
        val startNew = shouldStartNewAssistantBubble()
        val currentMessages = _messages.value.toMutableList()
        if (currentMessages.isNotEmpty() &&
            currentMessages.last().role == MessageRole.ASSISTANT &&
            !startNew
        ) {
            val lastMessage = currentMessages.last()
            val cleanedIncoming = sanitizeAssistantText(text)
            if (cleanedIncoming.isBlank()) return
            val existing = lastMessage.content
            val merged = when {
                existing.isBlank() -> cleanedIncoming
                cleanedIncoming == existing -> return
                // 累积全文：用更长文本替换，避免重复拼接
                cleanedIncoming.startsWith(existing) -> cleanedIncoming
                cleanedIncoming.contains(existing) &&
                    cleanedIncoming.length > existing.length -> cleanedIncoming
                existing.startsWith(cleanedIncoming) -> return
                existing.contains(cleanedIncoming) -> return
                else -> sanitizeAssistantText(existing + cleanedIncoming)
            }
            if (merged.isBlank() || merged == existing) return
            currentMessages[currentMessages.size - 1] = lastMessage.copy(content = merged)
            _messages.value = currentMessages
        } else {
            forceNextAssistantBubble = false
            val cleaned = sanitizeAssistantText(text)
            if (cleaned.isBlank()) return
            addMessage(Message(
                role = MessageRole.ASSISTANT,
                content = cleaned
            ))
        }
    }

    /**
     * 同步助手消息（用于确保 sentence_end 的完整性）
     */
    private fun syncAssistantMessage(text: String) {
        if (shouldUsePhotoRoundAssistantBubble()) {
            updatePhotoRoundAssistantText(text, preferReplace = true)
            return
        }
        val cleaned = sanitizeAssistantText(text)
        if (cleaned.isBlank()) return
        val startNew = shouldStartNewAssistantBubble()
        val currentMessages = _messages.value.toMutableList()
        if (currentMessages.isNotEmpty() &&
            currentMessages.last().role == MessageRole.ASSISTANT &&
            !startNew
        ) {
            val lastMessage = currentMessages.last()
            if (lastMessage.content.length < cleaned.length) {
                currentMessages[currentMessages.size - 1] = lastMessage.copy(content = cleaned)
                _messages.value = currentMessages
            }
        } else {
            forceNextAssistantBubble = false
            addMessage(Message(role = MessageRole.ASSISTANT, content = cleaned))
        }
    }

    /**
     * 停止自动对话模式
     */
    fun stopAutoConversation() {
        if (_isConnected.value) {
            mqttManager.sendAbort("stop_auto_mode")
        }
        enterStandby("stop_auto_mode", notifyServer = true)
        Log.d(TAG, "停止自动对话模式")
    }

    /**
     * 添加消息到列表
     */
    private fun addMessage(message: Message) {
        val updated = _messages.value + message
        _messages.value = if (updated.size > MAX_MESSAGES) {
            updated.takeLast(MAX_MESSAGES)
        } else {
            updated
        }
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 清除对话历史
     */
    fun clearMessages() {
        _messages.value = emptyList()
    }

    /**
     * 重新连接
     */
    fun reconnect() {
        sessionManager.disconnect()
        connect()
    }

    /**
     * 测试音频播放
     */
    fun testAudioPlayback() {
        audioManager.testAudioPlayback()
    }

    override fun onCleared() {
        XiaozhiAppEvents.photoKeyGate = { true }
        XiaozhiAppEvents.photoSessionRecoverHandler = null
        XiaozhiAppEvents.photoRoundSuccessHandler = null
        cancelPhotoMcpWait()
        super.onCleared()
        pendingWakeRetryJob?.cancel()
        pendingWakeRetryJob = null
        pendingRecordKeyRetryJob?.cancel()
        pendingRecordKeyRetryJob = null
        cancelSpeakingWatchdog()
        cancelSessionEndFallback()
        isAudioInitialized = false
        pendingAutoStart = false
        audioManager.cleanup()
    }
}

