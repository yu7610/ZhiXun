package com.powerchina.zhixun.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
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
import com.powerchina.zhixun.network.WebSocketEvent
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents
import com.powerchina.zhixun.xiaozhi.PhotoResult
import com.powerchina.zhixun.xiaozhi.XiaozhiSessionManager
import com.powerchina.zhixun.xiaozhi.VoiceFlowLog
import com.powerchina.zhixun.xiaozhi.wake.WakePhraseMatcher
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeCoordinator
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

/**
 * 对话ViewModel
 */
class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ConversationViewModel"
        /** 说「退下」后过滤服务器回复：adb logcat -s SessionEndReply */
        private const val SESSION_END_TAG = "SessionEndReply"
        /** 全流程诊断：adb logcat -s VoiceFlow */
        private const val SPEAKING_WATCHDOG_MS = 20_000L
        private const val SPEAKING_NO_AUDIO_MS = 3_000L
        /** detect 后忽略服务器问候 TTS，避免卡在「回复中」（服务器回显可能延迟 10s+） */
        private const val WAKE_GREETING_SUPPRESS_MS = 15_000L
        /** 服务端 listen 会话约 30s 超时，对话聆听中需 stop+start 续期（与 WakeSTT 一致） */
        private const val LISTEN_KEEPALIVE_INTERVAL_MS = 12_000L

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
    }

    private val gson = Gson()
    private val sessionManager = XiaozhiSessionManager.getInstance(application)
    private val webSocketManager = sessionManager.webSocketManager
    private val audioManager = EnhancedAudioManager(application)

    // 状态管理
    private val _state = MutableStateFlow(ConversationState.IDLE)
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** WebSocket / 唤醒监听均就绪后，待机 UI 才可显示 */
    private val _isStandbyReady = MutableStateFlow(false)
    val isStandbyReady: StateFlow<Boolean> = _isStandbyReady.asStateFlow()

    /** 断线后等待自动重连（此期间 UI 不显示待机） */
    private val _isAwaitingReconnect = MutableStateFlow(false)
    val isAwaitingReconnect: StateFlow<Boolean> = _isAwaitingReconnect.asStateFlow()

    /** 唤醒问候 TTS 播放中（状态仍为 LISTENING，UI 显示「回复中」） */
    private val _isWakeGreetingPlaying = MutableStateFlow(false)
    val isWakeGreetingPlaying: StateFlow<Boolean> = _isWakeGreetingPlaying.asStateFlow()

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

    // WebSocket 已连接但音频尚未就绪时，延后自动开麦
    private var pendingAutoStart = false

    // 对话页是否在前台（离开聊天页时暂停，返回时恢复）
    private var conversationUiActive = false

    // 离开聊天页前是否在语音对话中（用于返回后恢复聆听）
    private var shouldResumeOnUiReturn = false
    private var resumeManualListening = false

    // 语音唤醒「你好，智询」后待进入对话
    private var pendingVoiceWake = false

    // 物理录音键（138）待连接后开麦
    private var pendingRecordKeyStart = false
    private var pendingRecordKeyRetryJob: Job? = null
    private var pendingRecordKeyRetryCount = 0

    private var pendingWakeRetryJob: Job? = null
    private var pendingWakeRetryCount = 0

    private var speakingWatchdogJob: Job? = null
    private var listenHandoffJob: Job? = null
    private var listeningKeepaliveJob: Job? = null
    private var hasLoggedFirstAudioFrame = false

    /** 语音唤醒 → 对话开麦交接中，忽略服务器 TTS/STT 回显 */
    private var wakeConversationHandoff = false
    /** detect 后短暂忽略服务器问候 TTS/LLM，保持聆听态 */
    private var suppressWakeGreetingUntilMs = 0L

    /** 用户说了「退下」等：等待服务器回复 TTS，播完后再待机；此期间不主动 stopListening/断连 */
    private var pendingSessionEnd = false
    private var sessionEndFallbackJob: Job? = null
    private var standbyReadyPollJob: Job? = null

    init {
        startEventListening()
        viewModelScope.launch {
            sessionManager.isConnected.collect { connected ->
                _isConnected.value = connected
                if (connected) {
                    tryHandlePendingVoiceWake()
                    tryHandlePendingRecordKeyStart()
                    if (_state.value == ConversationState.CONNECTING) {
                        _state.value = ConversationState.IDLE
                    }
                    tryStartAutoConversationIfNeeded()
                } else {
                    _isAwaitingReconnect.value = webSocketManager.isAutoReconnectEnabled() &&
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
    }

    private fun onPhotoUploadResult(result: PhotoResult) {
        result.file?.let { file ->
            addMessage(
                Message(
                    role = MessageRole.USER,
                    content = "",
                    imagePath = file.absolutePath,
                ),
            )
        }
        result.uploadResult
            .onSuccess {
                Log.i(TAG, "照片已上传小智 ${result.file?.name}")
                _errorMessage.value = null
                showPhotoVisionDescription(result.visionDescription)
                if (_state.value == ConversationState.IDLE ||
                    _state.value == ConversationState.LISTENING
                ) {
                    _state.value = ConversationState.PROCESSING
                }
                XiaozhiAppEvents.endPhotoSession()
                resumeWakeListeningIfNeeded()
            }
            .onFailure { err ->
                XiaozhiAppEvents.endPhotoSession()
                _errorMessage.value = sanitizePhotoError(err.message)
            }
    }

    private fun showPhotoVisionDescription(description: String?) {
        val text = description?.trim().orEmpty()
        if (text.isBlank()) {
            Log.w(TAG, "视觉描述为空，无法展示")
            return
        }
        val last = _messages.value.lastOrNull()
        if (last?.role == MessageRole.ASSISTANT && last.content.trim() == text) {
            return
        }
        addMessage(
            Message(
                role = MessageRole.ASSISTANT,
                content = text,
            ),
        )
        Log.i(TAG, "展示视觉描述 len=${text.length}")
    }

    private fun sanitizePhotoError(message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isBlank()) return "照片上传失败"
        if (raw.startsWith("{") && raw.contains("\"success\"")) return "照片识别失败，请重试"
        return raw
    }

    private fun isLikelyVisionJsonEcho(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("{") &&
            (trimmed.contains("\"success\"") || trimmed.contains("\"filename\""))
    }

    /** 过滤 LLM/TTS 流里泄漏的工具调用标记（如 % get_weather..） */
    private fun sanitizeAssistantText(text: String): String {
        var result = text
        result = ASSISTANT_TOOL_MARKER.replace(result, "")
        result = ASSISTANT_TOOL_JSON.replace(result, "")
        if ('%' in result) {
            result = ASSISTANT_GENERIC_TOOL.replace(result, "")
        }
        result = ASSISTANT_LEADING_JUNK.replace(result, "")
        return result.replace(Regex("""[ \t]{2,}"""), " ").trim()
    }

    /** 本地已展示完整视觉描述时，避免 TTS/LLM 重复追加同一段文字 */
    private fun shouldApplyServerAssistantText(incoming: String): Boolean {
        val text = incoming.trim()
        if (text.isBlank()) return false
        val last = _messages.value.lastOrNull() ?: return true
        if (last.role != MessageRole.ASSISTANT) return true
        val current = last.content.trim()
        if (current.isBlank()) return true
        if (current == text || current.contains(text) || text.contains(current)) {
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
        if (!_isConnected.value) blockers.add("disconnected")
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

    /** 已在开麦聆听时收到的 TTS/音频多为上一轮 abort 后的迟来回显 */
    private fun shouldIgnoreStaleReplyWhileListening(): Boolean {
        return _state.value == ConversationState.LISTENING &&
            isAutoMode &&
            audioManager.isRecording() &&
            !pendingSessionEnd &&
            !isWakeGreetingWindow() &&
            !_isWakeGreetingPlaying.value
    }

    private fun isWakeHandoffInProgress(): Boolean {
        return wakeConversationHandoff ||
            pendingVoiceWake ||
            listenHandoffJob?.isActive == true ||
            XiaozhiWakeCoordinator.isWakeHandoffInProgress()
    }

    private fun setWakeGreetingPlaying(playing: Boolean) {
        if (_isWakeGreetingPlaying.value == playing) return
        _isWakeGreetingPlaying.value = playing
        VoiceFlowLog.step("wake.greetingPlay", "playing=$playing")
    }

    private fun isLikelyEmotionOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        return trimmed.none { it in '\u4e00'..'\u9fff' || it.isLetter() }
    }

    /** 下行 TTS：对话态直接播放；唤醒问候窗口内 IDLE 也播放（开麦交接期间） */
    private fun shouldPlayDownlinkAudio(): Boolean {
        if (isWakeGreetingWindow()) {
            return _state.value != ConversationState.CONNECTING
        }
        return when (_state.value) {
            ConversationState.SPEAKING,
            ConversationState.LISTENING,
            ConversationState.PROCESSING -> true
            else -> false
        }
    }

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
                    isWakeGreetingWindow() ||
                    _isWakeGreetingPlaying.value ||
                    audioManager.isPlaying()
                ) {
                    continue
                }
                val now = System.currentTimeMillis()
                if (now - lastRenew < LISTEN_KEEPALIVE_INTERVAL_MS) continue
                Log.d(TAG, "续期对话 listen 会话 (stop+start)")
                VoiceFlowLog.step(
                    "listen.keepalive",
                    "session=${webSocketManager.getSessionId()}",
                )
                webSocketManager.sendStopListening()
                webSocketManager.sendStartListening("auto")
                lastRenew = System.currentTimeMillis()
            }
        }
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
        cancelListenHandoff()
        audioManager.stopRecording()
        audioManager.releaseRecorderOnly()
        isAudioInitialized = false
        XiaozhiWakeForegroundService.prepareWakeAudioCapture(getApplication())
        Log.d(TAG, "结束语等待：已释放麦克风，预初始化唤醒采集")
    }

    private fun pauseConversationForUi() {
        val wakeHandoff = pendingVoiceWake || XiaozhiWakeCoordinator.isWakeHandoffInProgress()
        if (wakeHandoff || XiaozhiAppEvents.isPhotoSessionActive()) {
            Log.d(TAG, "唤醒交接/拍照会话中，跳过 UI 暂停清理")
            return
        }

        val current = _state.value
        resumeManualListening = current == ConversationState.LISTENING && !isAutoMode
        shouldResumeOnUiReturn = isAutoMode || resumeManualListening

        audioManager.stopRecording()
        audioManager.stopPlaying()
        audioManager.releaseRecorderOnly()
        isAudioInitialized = false
        when (current) {
            ConversationState.LISTENING -> webSocketManager.sendStopListening()
            ConversationState.SPEAKING,
            ConversationState.PROCESSING -> webSocketManager.sendAbort("ui_pause")
            else -> Unit
        }
        _state.value = ConversationState.IDLE
        Log.d(TAG, "对话页离开 → 待机 resumeOnReturn=$shouldResumeOnUiReturn")
        resumeWakeListeningIfNeeded()
    }

    private fun resumeConversationForUi() {
        if (pendingVoiceWake || XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
            Log.d(TAG, "唤醒交接中，跳过 UI 恢复")
            shouldResumeOnUiReturn = false
            resumeManualListening = false
            return
        }
        Log.d(TAG, "对话页返回 resumeOnReturn=$shouldResumeOnUiReturn connected=${_isConnected.value}")
        if (!_isConnected.value) return
        if (!shouldResumeOnUiReturn) return
        val manual = resumeManualListening
        shouldResumeOnUiReturn = false
        resumeManualListening = false
        if (isAutoMode) {
            tryStartAutoConversationIfNeeded()
        } else if (manual) {
            startListening()
        }
        tryHandlePendingVoiceWake()
    }

    /**
     * 物理录音键（138）：
     * - 待机/未连接 → 连接并进入聆听
     * - 聆听/回复中 → 结束对话，进入待机
     */
    fun onRecordKeyPressed() {
        val current = _state.value
        when (current) {
            ConversationState.LISTENING,
            ConversationState.PROCESSING,
            ConversationState.SPEAKING -> {
                XiaozhiAppEvents.acknowledgeVoiceKeyEvent()
                stopConversationFromVoiceKey()
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

    private fun stopConversationFromVoiceKey() {
        cancelListenHandoff()
        pendingRecordKeyStart = false
        pendingVoiceWake = false
        pendingAutoStart = false
        pendingSessionEnd = false
        XiaozhiAppEvents.acknowledgeVoiceKeyEvent()
        cancelSpeakingWatchdog()
        cancelSessionEndFallback()
        isAutoMode = false
        audioManager.stopRecording()
        audioManager.stopPlaying()
        if (_isConnected.value) {
            webSocketManager.sendStopListening()
            webSocketManager.sendAbort("voice_key_stop")
        }
        stopListeningKeepalive()
        _state.value = ConversationState.IDLE
        Log.i(TAG, "录音键：结束对话 → 待机")
        prepareStandbyWakeListening()
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
        Log.i(TAG, "onVoiceWakeDetected 关键词=${WakePhraseMatcher.WAKE_PHRASE}")
        logFlow("wake.detected", "关键词=${WakePhraseMatcher.WAKE_PHRASE}")
        wakeConversationHandoff = true
        isAutoMode = true
        pendingVoiceWake = true
        pendingRecordKeyStart = false
        pendingWakeRetryCount = 0
        shouldResumeOnUiReturn = false
        resumeManualListening = false
        audioManager.stopPlaying()
        audioManager.stopRecording()
        webSocketManager.sendAbort("wake_handoff")
        _state.value = ConversationState.IDLE
        pauseWakeListening()
        XiaozhiWakeForegroundService.releaseMicrophoneForConversation(getApplication())
        audioManager.releaseRecorderOnly()
        isAudioInitialized = false
        initializeAudio()
        connect()
        tryHandlePendingVoiceWake()
    }

    private fun tryHandlePendingVoiceWake() {
        if (!pendingVoiceWake) return
        if (!conversationUiActive) {
            VoiceFlowLog.decision("wake.pending", "开麦", false, "对话页未就绪")
            Log.d(TAG, "pendingWake: 对话页未就绪")
            return
        }
        if (!_isConnected.value) {
            VoiceFlowLog.decision("wake.pending", "开麦", false, "WebSocket 未连接")
            Log.d(TAG, "pendingWake: WebSocket 未连接")
            return
        }
        if (_state.value != ConversationState.IDLE) {
            VoiceFlowLog.warn("wake.pending", "非 IDLE(${_state.value})，重置后重试")
            Log.d(TAG, "pendingWake: 状态=${_state.value}，重置为 IDLE")
            audioManager.stopPlaying()
            audioManager.stopRecording()
            webSocketManager.sendAbort("wake_handoff")
            transitionState(ConversationState.IDLE, "pendingWake_reset")
        }
        if (!ensureAudioReadyForPendingWake()) {
            VoiceFlowLog.decision("wake.pending", "开麦", false, "音频未就绪")
            return
        }

        pendingVoiceWake = false
        pendingWakeRetryJob?.cancel()
        pendingWakeRetryJob = null
        pendingWakeRetryCount = 0
        pauseWakeListening()
        showWakePhraseAsUserMessage(WakePhraseMatcher.WAKE_PHRASE)
        webSocketManager.sendWakeWordDetected(WakePhraseMatcher.WAKE_PHRASE)
        scheduleWakeGreetingSuppression()
        Log.i(TAG, "pendingWake → 发送 detect + 开始自动对话")
        logFlow("wake.detect.sent", "detect=${WakePhraseMatcher.WAKE_PHRASE}")
        startAutoConversation()
    }

    private fun scheduleWakeGreetingSuppression() {
        suppressWakeGreetingUntilMs = System.currentTimeMillis() + WAKE_GREETING_SUPPRESS_MS
        VoiceFlowLog.step(
            "wake.greetingWindow",
            "开启 ${WAKE_GREETING_SUPPRESS_MS}ms，until=$suppressWakeGreetingUntilMs",
        )
    }

    /** detect 后一段时间内视为唤醒问候窗口：播放问候 TTS，但不切 SPEAKING、不打断开麦 */
    private fun isWakeGreetingWindow(): Boolean =
        System.currentTimeMillis() < suppressWakeGreetingUntilMs

    private fun clearWakeGreetingSuppression() {
        if (suppressWakeGreetingUntilMs > 0L) {
            VoiceFlowLog.step("wake.greetingWindow", "清除")
        }
        suppressWakeGreetingUntilMs = 0L
        setWakeGreetingPlaying(false)
    }

    private fun shouldSuppressWakeHandoffEcho(): Boolean =
        isWakeGreetingWindow() ||
            wakeConversationHandoff ||
            pendingVoiceWake ||
            listenHandoffJob?.isActive == true ||
            XiaozhiWakeCoordinator.isWakeHandoffInProgress()

    private fun clearWakeConversationHandoff(reason: String) {
        if (!wakeConversationHandoff &&
            !XiaozhiWakeCoordinator.isWakeHandoffInProgress()
        ) {
            return
        }
        wakeConversationHandoff = false
        XiaozhiWakeCoordinator.clearWakeHandoff(reason)
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
        if (XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
            Log.d(TAG, "唤醒交接中，不恢复后台监听")
            return
        }
        if (XiaozhiAppEvents.isPhotoSessionActive()) {
            Log.d(TAG, "拍照会话中，不恢复后台监听")
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
     * 对话页就绪：连接 WebSocket；待机时不抢唤醒 mic。
     */
    fun onConversationScreenReady() {
        connect()
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

    private fun cancelSessionEndFallback() {
        sessionEndFallbackJob?.cancel()
        sessionEndFallbackJob = null
    }

    private fun scheduleSessionEndFallback() {
        cancelSessionEndFallback()
        sessionEndFallbackJob = viewModelScope.launch {
            delay(20_000)
            if (!pendingSessionEnd) return@launch
            Log.w(TAG, "结束语等待超时，进入待机")
            Log.w(SESSION_END_TAG, "20s 内未收到完整结束语，强制进入待机")
            pendingSessionEnd = false
            enterStandby("session_end_timeout", notifyServer = true, fastWake = true)
        }
    }

    private fun cancelSpeakingWatchdog() {
        speakingWatchdogJob?.cancel()
        speakingWatchdogJob = null
    }

    private fun scheduleSpeakingWatchdog() {
        cancelSpeakingWatchdog()
        VoiceFlowLog.step("tts.watchdog", "启动 noAudio=${SPEAKING_NO_AUDIO_MS}ms total=${SPEAKING_WATCHDOG_MS}ms")
        speakingWatchdogJob = viewModelScope.launch {
            delay(SPEAKING_NO_AUDIO_MS)
            if (_state.value == ConversationState.SPEAKING && !audioManager.isPlaying()) {
                VoiceFlowLog.warn(
                    "tts.watchdog",
                    "${SPEAKING_NO_AUDIO_MS}ms 无音频播放 → finishSpeakingTurn | ${flowContext()}",
                )
                Log.w(TAG, "TTS ${SPEAKING_NO_AUDIO_MS}ms 无音频，恢复聆听")
                audioManager.stopPlaying()
                finishSpeakingTurn("watchdog_no_audio")
                return@launch
            }
            delay(SPEAKING_WATCHDOG_MS - SPEAKING_NO_AUDIO_MS)
            if (_state.value != ConversationState.SPEAKING) return@launch
            VoiceFlowLog.warn(
                "tts.watchdog",
                "${SPEAKING_WATCHDOG_MS}ms 未收到 stop → finishSpeakingTurn | ${flowContext()}",
            )
            Log.w(TAG, "TTS 超时未收到 stop，强制恢复")
            audioManager.stopPlaying()
            finishSpeakingTurn("watchdog_timeout")
        }
    }

    private fun finishSpeakingTurn(trigger: String = "tts_stop") {
        cancelSpeakingWatchdog()
        VoiceFlowLog.step("tts.finish", "trigger=$trigger | ${flowContext()}")
        if (_state.value == ConversationState.IDLE &&
            (isWakeGreetingWindow() || isWakeHandoffInProgress())
        ) {
            audioManager.stopPlaying()
            Log.d(TAG, "唤醒交接/问候窗口内忽略 IDLE TTS finish")
            return
        }
        if (shouldSuppressWakeHandoffEcho() && !isWakeGreetingWindow()) {
            VoiceFlowLog.decision("tts.finish", "处理 stop", false, "唤醒交接中")
            Log.d(TAG, "唤醒交接中，忽略 TTS stop")
            return
        }
        // 唤醒问候播完：只停播放，保持 LISTENING 开麦
        if (_state.value == ConversationState.LISTENING &&
            isAutoMode &&
            !pendingSessionEnd &&
            audioManager.isRecording() &&
            (isWakeGreetingWindow() || _isWakeGreetingPlaying.value)
        ) {
            audioManager.stopPlaying()
            setWakeGreetingPlaying(false)
            Log.d(TAG, "唤醒问候播完，继续聆听")
            return
        }
        // 已在自动聆听时收到的 stop 多为 detect 后迟到的问候回显，保持开麦
        if (_state.value == ConversationState.LISTENING &&
            isAutoMode &&
            !pendingSessionEnd &&
            audioManager.isRecording()
        ) {
            VoiceFlowLog.decision("tts.finish", "处理 stop", false, "聆听中且正在录音，忽略迟来问候")
            audioManager.stopPlaying()
            Log.d(TAG, "聆听中忽略迟来 TTS stop")
            return
        }
        if (pendingSessionEnd) {
            audioManager.stopRecording()
            pendingSessionEnd = false
            cancelSessionEndFallback()
            Log.i(TAG, "结束语播完 → 待机（不主动断开 WebSocket）")
            Log.i(SESSION_END_TAG, "结束语 TTS 播完，进入待机")
            logFlow("sessionEnd.ttsDone", "trigger=$trigger")
            enterStandby("session_end_tts", notifyServer = true, fastWake = true)
            return
        }
        if (isAutoMode && _isConnected.value && conversationUiActive) {
            audioManager.stopPlaying()
            if (_state.value == ConversationState.SPEAKING) {
                audioManager.stopRecording()
            }
            if (audioManager.isRecording()) {
                transitionState(ConversationState.LISTENING, "tts_finish_resume_listening")
                Log.i(TAG, "TTS 结束 → 恢复聆听")
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
        audioManager.stopRecording()
        enterStandby("tts_end", notifyServer = false)
    }

    /** 对话进行中（LISTENING/PROCESSING/SPEAKING）断线：自动重连并恢复开麦，而非进入待机 */
    private fun shouldReconnectAfterConversationDisconnect(): Boolean {
        if (!webSocketManager.isAutoReconnectEnabled() || !conversationUiActive) return false
        if (pendingSessionEnd) return false
        val inConversation = _state.value == ConversationState.LISTENING ||
            _state.value == ConversationState.PROCESSING ||
            _state.value == ConversationState.SPEAKING
        return inConversation && isAutoMode
    }

    private fun beginWsReconnectAfterConversationDisconnect() {
        pendingAutoStart = true
        isAutoMode = true
        XiaozhiWakeForegroundService.releaseMicrophoneForConversation(getApplication())
        _isAwaitingReconnect.value = true
        _isStandbyReady.value = false
        transitionState(ConversationState.CONNECTING, "ws_disconnect_conversation")
        Log.i(TAG, "对话中断连，重连后恢复自动对话")
        VoiceFlowLog.warn(
            "ws.disconnected",
            "对话中断连 pendingAutoStart=true | ${flowContext()}",
        )
        updateStandbyReady()
        scheduleStandbyReadyPoll()
    }

    /** 结束当前对话轮次，回到待机（不发 listen start） */
    private fun enterStandby(
        reason: String,
        notifyServer: Boolean = false,
        fastWake: Boolean = false,
    ) {
        cancelListenHandoff()
        cancelSpeakingWatchdog()
        cancelSessionEndFallback()
        stopListeningKeepalive()
        pendingSessionEnd = false
        pendingVoiceWake = false
        pendingAutoStart = false
        isAutoMode = false
        setWakeGreetingPlaying(false)
        audioManager.stopRecording()
        audioManager.stopPlaying()
        if (notifyServer && _isConnected.value) {
            webSocketManager.sendStopListening()
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
            _errorMessage.value = "音频系统初始化失败，请确认已授予麦克风权限"
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

    private fun tryStartAutoConversationIfNeeded() {
        if (pendingVoiceWake) {
            tryHandlePendingVoiceWake()
            return
        }
        if (pendingRecordKeyStart) {
            tryHandlePendingRecordKeyStart()
            return
        }
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
        // 监听WebSocket事件 - 确保在WebSocket连接之前就开始监听
        viewModelScope.launch {
            Log.d(TAG, "开始监听 WebSocket + 音频事件")
            webSocketManager.events.collect { event ->
                handleWebSocketEvent(event)
            }
        }

        // 监听音频事件
        viewModelScope.launch {
            audioManager.audioEvents.collect { event ->
                handleAudioEvent(event)
            }
        }
    }

    /**
     * 用户确认激活后连接 WebSocket
     */
    fun onActivationConfirmed() {
        Log.d(TAG, "用户确认激活，开始连接 WebSocket")
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
        sessionManager.reloadConfig()
        config = configManager.loadConfig()
        if (sessionManager.isConnected.value) {
            _isConnected.value = true
            if (_state.value == ConversationState.CONNECTING) {
                _state.value = ConversationState.IDLE
            }
            tryHandlePendingVoiceWake()
            tryHandlePendingRecordKeyStart()
            if (_state.value == ConversationState.IDLE) {
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
     * 处理WebSocket事件
     */
    private fun handleWebSocketEvent(event: WebSocketEvent) {
        Log.v(TAG, "WS事件 ${event::class.simpleName} state=${_state.value}")
        when (event) {
            is WebSocketEvent.HelloReceived -> {
                Log.d(TAG, "握手 HelloReceived")
            }

            is WebSocketEvent.Connected -> {
                Log.i(TAG, "WS Connected state=${_state.value} ui=$conversationUiActive pendingWake=$pendingVoiceWake")
                logFlow("ws.connected", "session=${webSocketManager.getSessionId()}")
                _isConnected.value = true
                _isAwaitingReconnect.value = false
                _errorMessage.value = null
                if (_state.value == ConversationState.CONNECTING) {
                    _state.value = ConversationState.IDLE
                }
                tryHandlePendingVoiceWake()
                tryHandlePendingRecordKeyStart()
                if (pendingAutoStart) {
                    initializeAudio()
                } else {
                    tryStartAutoConversationIfNeeded()
                }
                if (!XiaozhiWakeForegroundService.isWakeListeningHealthy()) {
                    resumeWakeListeningIfNeeded()
                } else {
                    updateStandbyReady()
                }
            }

            is WebSocketEvent.Disconnected -> {
                Log.w(TAG, "WS Disconnected state=${_state.value} pendingSessionEnd=$pendingSessionEnd")
                logFlow("ws.disconnected", "autoReconnect=${webSocketManager.isAutoReconnectEnabled()}")
                if (pendingSessionEnd) {
                    Log.i(
                        SESSION_END_TAG,
                        "[disconnect] WebSocket 断开 state=${_state.value}，等待 TTS 播完或超时",
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
                if (keepRecordKeyFlow) {
                    isAutoMode = true
                    _state.value = ConversationState.CONNECTING
                } else if (pendingSessionEnd &&
                    (_state.value == ConversationState.SPEAKING || _state.value == ConversationState.PROCESSING)
                ) {
                    Log.i(TAG, "服务器断线，等待结束语播完再待机")
                } else if (shouldReconnectAfterConversationDisconnect()) {
                    beginWsReconnectAfterConversationDisconnect()
                } else if (_state.value == ConversationState.IDLE &&
                    webSocketManager.isAutoReconnectEnabled()
                ) {
                    _isAwaitingReconnect.value = true
                    _isStandbyReady.value = false
                    transitionState(ConversationState.CONNECTING, "ws_disconnect_reconnect")
                    Log.i(TAG, "WS 断开 → 等待自动重连（不显示待机）")
                    VoiceFlowLog.warn(
                        "ws.disconnected",
                        "待机中断连，等待重连 | blockers=${standbyReadyBlockers().joinToString(",")}",
                    )
                    updateStandbyReady()
                    scheduleStandbyReadyPoll()
                } else {
                    _isAwaitingReconnect.value = false
                    enterStandby("ws_disconnect", notifyServer = false)
                }
            }

            is WebSocketEvent.TextMessage -> {
                handleTextMessage(event.message)
            }

            is WebSocketEvent.BinaryMessage -> {
                handleBinaryMessage(event.data)
            }
            
            is WebSocketEvent.MCPMessage -> Unit

            is WebSocketEvent.Error -> {
                Log.e(TAG, "WebSocket错误: ${event.error}")
                logSessionEndServerReply("error", event.error)
                _errorMessage.value = event.error
                _state.value = ConversationState.IDLE
                audioManager.stopRecording()
                audioManager.stopPlaying()
            }
        }
    }

    /**
     * 后台唤醒监听或交接期间，忽略共享 WebSocket 上的对话消息。
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

            Log.d(TAG, "消息 type=$type session=$sessionId state=${_state.value}")

            when (type) {
                "stt" -> {
                    if (!conversationUiActive) return@handleTextMessage
                    if (shouldSuppressWakeHandoffEcho()) {
                        VoiceFlowLog.decision("msg.stt", "处理", false, "handoff/greet抑制")
                        Log.d(TAG, "唤醒交接中，忽略 STT")
                        return@handleTextMessage
                    }
                    val text = json.get("text")?.asString
                    Log.d(TAG, "STT: $text")
                    if (!text.isNullOrEmpty() && XiaozhiAppEvents.isPhotoSessionActive()) {
                        if (isLikelyVisionJsonEcho(text)) {
                            Log.d(TAG, "拍照会话中忽略视觉 JSON 回显")
                            return@handleTextMessage
                        }
                        Log.d(TAG, "拍照会话中忽略 STT 回显")
                        return@handleTextMessage
                    }
                    if (!text.isNullOrEmpty() && WakePhraseMatcher.matches(text)) {
                        Log.d(TAG, "忽略唤醒词 STT")
                        return@handleTextMessage
                    }
                    if (!text.isNullOrEmpty() && WakePhraseMatcher.isSessionEndPhrase(text)) {
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
                        isAutoMode = false
                        transitionState(ConversationState.PROCESSING, "session_end_stt")
                        beginSessionEndWindDown()
                        scheduleSessionEndFallback()
                        return@handleTextMessage
                    }
                    if (!text.isNullOrEmpty() && !text.contains("请登录控制面板")) {
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
                    val emotion = json.get("emotion")?.asString
                    val text = json.get("text")?.asString
                    if (isWakeGreetingWindow() && !text.isNullOrBlank() && isLikelyEmotionOnly(text)) {
                        Log.d(TAG, "唤醒问候窗口内忽略纯表情 LLM")
                        return@handleTextMessage
                    }
                    Log.d(TAG, "LLM emotion=$emotion text=$text")
                    if (!text.isNullOrEmpty() && shouldApplyServerAssistantText(text)) {
                        updateAssistantMessage(text)
                    }
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
                            if (shouldIgnoreStaleReplyWhileListening()) {
                                Log.d(TAG, "聆听中忽略迟来 TTS sentence_start")
                                return@handleTextMessage
                            }
                            // TTS句子开始，显示要播放的文本
                            val text = json.get("text")?.asString
                            if (!text.isNullOrEmpty() && shouldApplyServerAssistantText(text)) {
                                updateAssistantMessage(text)
                            }
                        }
                        "sentence_end" -> {
                            if (shouldIgnoreStaleReplyWhileListening()) {
                                Log.d(TAG, "聆听中忽略迟来 TTS sentence_end")
                                return@handleTextMessage
                            }
                            // TTS句子结束，有时包含完整的句子内容
                            val text = json.get("text")?.asString
                            if (!text.isNullOrEmpty() && shouldApplyServerAssistantText(text)) {
                                // 检查是否需要更新（如果sentence_start已经包含了这部分内容则跳过，或者直接替换为更完整的text）
                                // 这里简单处理：如果当前最后一条助手消息内容不包含这段text，则更新/追加
                                Log.d(TAG, "TTS sentence_end: $text")
                                // 注意：根据不同服务端的实现，sentence_end 可能包含整句，也可能只是最后一段
                                // 为了保险，这里我们信任 sentence_end 的完整性，如果它比当前存的长，就用它
                                syncAssistantMessage(text)
                            }
                        }
                        "start" -> {
                            if (!conversationUiActive) return@handleTextMessage
                            if (listenHandoffJob?.isActive == true && !isWakeGreetingWindow()) {
                                VoiceFlowLog.decision("msg.tts.start", "→SPEAKING", false, "开麦交接中")
                                Log.d(TAG, "开麦交接中，忽略 TTS start")
                                return@handleTextMessage
                            }
                            if (_state.value == ConversationState.LISTENING &&
                                isAutoMode &&
                                audioManager.isRecording()
                            ) {
                                if (isWakeGreetingWindow()) {
                                    setWakeGreetingPlaying(true)
                                    Log.d(TAG, "唤醒问候播放中，保持聆听")
                                    return@handleTextMessage
                                }
                                VoiceFlowLog.decision(
                                    "msg.tts.start",
                                    "→SPEAKING",
                                    false,
                                    "已在聆听，忽略迟来问候",
                                )
                                Log.d(TAG, "已在聆听，忽略迟来 TTS start")
                                return@handleTextMessage
                            }
                            if (listenHandoffJob?.isActive == true && isWakeGreetingWindow()) {
                                Log.d(TAG, "唤醒问候到达（开麦交接中），保持 IDLE 播放")
                                return@handleTextMessage
                            }
                            val canPlayTts = _state.value == ConversationState.PROCESSING ||
                                _state.value == ConversationState.SPEAKING ||
                                pendingSessionEnd ||
                                (_state.value == ConversationState.LISTENING && isAutoMode) ||
                                (isAutoMode && shouldSuppressWakeHandoffEcho())
                            if (!canPlayTts) {
                                VoiceFlowLog.decision(
                                    "msg.tts.start",
                                    "→SPEAKING",
                                    false,
                                    "state=${_state.value} auto=$isAutoMode pendingEnd=$pendingSessionEnd",
                                )
                                Log.d(TAG, "忽略非对话中的 TTS start")
                                return@handleTextMessage
                            }
                            transitionState(ConversationState.SPEAKING, "tts_start")
                            scheduleSpeakingWatchdog()
                            VoiceFlowLog.decision("msg.tts.start", "→SPEAKING", true, flowContext())
                            Log.d(TAG, "TTS start → SPEAKING")
                        }
                        "stop" -> {
                            if (_state.value == ConversationState.IDLE &&
                                (isWakeGreetingWindow() || isWakeHandoffInProgress())
                            ) {
                                audioManager.stopPlaying()
                                Log.d(TAG, "唤醒交接/问候窗口内忽略 IDLE TTS stop")
                                return@handleTextMessage
                            }
                            if (_state.value == ConversationState.LISTENING &&
                                isAutoMode &&
                                !pendingSessionEnd &&
                                (isWakeGreetingWindow() || _isWakeGreetingPlaying.value)
                            ) {
                                audioManager.stopPlaying()
                                setWakeGreetingPlaying(false)
                                Log.d(TAG, "唤醒问候 TTS stop，继续聆听")
                                return@handleTextMessage
                            }
                            if (_state.value == ConversationState.LISTENING &&
                                isAutoMode &&
                                !pendingSessionEnd
                            ) {
                                VoiceFlowLog.decision(
                                    "msg.tts.stop",
                                    "finishSpeakingTurn",
                                    false,
                                    "聆听中忽略迟来问候stop",
                                )
                                Log.d(TAG, "聆听中忽略迟来 TTS stop")
                                audioManager.stopPlaying()
                                return@handleTextMessage
                            }
                            if (!pendingSessionEnd) {
                                audioManager.stopPlaying()
                            }
                            VoiceFlowLog.step("msg.tts.stop", "pendingEnd=$pendingSessionEnd play=${audioManager.isPlaying()}")
                            Log.d(TAG, "TTS stop pendingSessionEnd=$pendingSessionEnd")
                            if (XiaozhiAppEvents.isPhotoSessionActive()) {
                                XiaozhiAppEvents.endPhotoSession()
                            }
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

    /**
     * 处理二进制消息（音频数据）
     */
    private fun handleBinaryMessage(data: ByteArray) {
        if (!conversationUiActive || shouldIgnoreConversationServerMessages()) return
        if (!shouldPlayDownlinkAudio()) {
            VoiceFlowLog.step(
                "msg.binary",
                "忽略待机态TTS音频 ${data.size}B state=${_state.value}",
            )
            Log.v(TAG, "忽略待机态 TTS 音频 ${data.size} bytes state=${_state.value}")
            return
        }
        if (pendingSessionEnd) {
            logSessionEndServerReply("binary", "tts_audio ${data.size} bytes")
        }
        if (isWakeGreetingWindow() && _state.value == ConversationState.LISTENING) {
            setWakeGreetingPlaying(true)
        }
        Log.v(TAG, "收到音频 ${data.size} bytes")
        audioManager.playAudio(data)
    }

    /**
     * 处理音频事件
     */
    private fun handleAudioEvent(event: AudioEvent) {
        when (event) {
            is AudioEvent.AudioData -> {
                // 只有在聆听状态才发送音频数据
                if (_state.value == ConversationState.LISTENING) {
                    if (!hasLoggedFirstAudioFrame) {
                        hasLoggedFirstAudioFrame = true
                        Log.d(TAG, "首帧音频上行 ${event.data.size}B")
                    }
                    webSocketManager.sendBinaryMessage(event.data)
                }
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
        if (_state.value != ConversationState.IDLE || !_isConnected.value) {
            return
        }
        if (!ensureRecordingReady()) return

        isAutoMode = false
        _state.value = ConversationState.LISTENING
        if (!audioManager.startRecording()) {
            _state.value = ConversationState.IDLE
            return
        }

        webSocketManager.sendStartListening("manual")
        pauseWakeListening()
        Log.i(TAG, "开始手动聆听")
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
            Log.d(TAG, "已在自动聆听，跳过")
            return true
        }
        if (_state.value != ConversationState.IDLE || !_isConnected.value) {
            Log.w(TAG, "无法开麦 state=${_state.value} connected=${_isConnected.value}")
            return false
        }
        if (!ensureRecordingReady()) {
            pendingAutoStart = true
            return false
        }
        if (listenHandoffJob?.isActive == true) {
            Log.d(TAG, "开麦交接进行中，跳过")
            return true
        }

        listenHandoffJob = viewModelScope.launch {
            performAutoConversationHandoff()
        }
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
        VoiceFlowLog.snapshot("handoff.begin", flowContext())
        XiaozhiWakeForegroundService.claimMicrophoneForConversation(getApplication())
        pauseWakeListening()

        isAutoMode = true
        hasLoggedFirstAudioFrame = false
        webSocketManager.sendStopListening()
        VoiceFlowLog.step("handoff", "sendStopListening + delay 150ms")
        delay(150)

        if (_state.value == ConversationState.SPEAKING) {
            VoiceFlowLog.warn("handoff", "SPEAKING 冲突（问候回显），abort 后继续开麦")
            webSocketManager.sendAbort("wake_greeting_echo")
            audioManager.stopPlaying()
            transitionState(ConversationState.IDLE, "handoff_clear_speaking")
            delay(150)
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

        webSocketManager.sendStartListening("auto")
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

    private fun cancelListenHandoff() {
        listenHandoffJob?.cancel()
        listenHandoffJob = null
        clearWakeConversationHandoff("handoff_cancelled")
        XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
    }

    /** 若 UI 为聆听中但麦克风未工作，回退待机避免假状态 */
    private fun scheduleListeningHealthCheck() {
        viewModelScope.launch {
            delay(400)
            if (_state.value == ConversationState.LISTENING && !audioManager.isRecording()) {
                Log.w(TAG, "聆听状态异常：麦克风未录音 → 待机")
                isAutoMode = false
                _state.value = ConversationState.IDLE
                webSocketManager.sendStopListening()
                XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
                prepareStandbyWakeListening()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureRecordingReady(): Boolean {
        if (!isAudioInitialized) {
            _errorMessage.value = "请先授予麦克风权限"
            return false
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
        webSocketManager.sendStopListening()
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
        webSocketManager.sendAbort(reason)

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
        webSocketManager.sendTextRequest(text)
        _state.value = ConversationState.PROCESSING
        Log.d(TAG, "发送文本: $text")
    }

    /**
     * 发送初始化消息（设备激活时使用，不添加到对话列表）
     */
    private fun sendInitializationMessage() {
        // 发送"初始化"文本消息，但不添加到对话列表
        webSocketManager.sendTextRequest("初始化")
        Log.d(TAG, "发送设备初始化消息")
    }

    /**
     * 打断当前对话
     */
    fun interrupt() {
        if (_isConnected.value) {
            webSocketManager.sendAbort("user_interrupt")
        }
        enterStandby("user_interrupt", notifyServer = false)
        Log.i(TAG, "用户打断对话")
    }

    /**
     * 断开连接并停止所有操作
     */
    fun disconnect() {
        Log.i(TAG, "用户主动断开连接")
        audioManager.stopRecording()
        audioManager.stopPlaying()
        sessionManager.shutdown()
        _state.value = ConversationState.IDLE
        isAutoMode = false
    }

    /**
     * 更新助手消息（用于流式输出）
     */
    private fun updateAssistantMessage(text: String) {
        val currentMessages = _messages.value.toMutableList()
        if (currentMessages.isNotEmpty() && currentMessages.last().role == MessageRole.ASSISTANT) {
            val lastMessage = currentMessages.last()
            val merged = sanitizeAssistantText(lastMessage.content + text)
            if (merged.isBlank() || merged == lastMessage.content) return
            currentMessages[currentMessages.size - 1] = lastMessage.copy(content = merged)
            _messages.value = currentMessages
        } else {
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
        val cleaned = sanitizeAssistantText(text)
        if (cleaned.isBlank()) return
        val currentMessages = _messages.value.toMutableList()
        if (currentMessages.isNotEmpty() && currentMessages.last().role == MessageRole.ASSISTANT) {
            val lastMessage = currentMessages.last()
            if (lastMessage.content.length < cleaned.length) {
                currentMessages[currentMessages.size - 1] = lastMessage.copy(content = cleaned)
                _messages.value = currentMessages
            }
        } else {
            addMessage(Message(role = MessageRole.ASSISTANT, content = cleaned))
        }
    }

    /**
     * 停止自动对话模式
     */
    fun stopAutoConversation() {
        if (_isConnected.value) {
            webSocketManager.sendAbort("stop_auto_mode")
        }
        enterStandby("stop_auto_mode", notifyServer = true)
        Log.d(TAG, "停止自动对话模式")
    }

    /**
     * 添加消息到列表
     */
    private fun addMessage(message: Message) {
        _messages.value = _messages.value + message
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
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