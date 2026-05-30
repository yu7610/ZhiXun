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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
        /** detect 后忽略服务器迟来问候控制信令，避免误结束；音频可能延迟 10s+ */
        private const val WAKE_GREETING_SUPPRESS_MS = 30_000L
        /** 待机时唤醒连接被空闲关闭后的快速重连宽限期，期间 UI 维持「待机」 */
        private const val STANDBY_RECONNECT_GRACE_MS = 6_000L
        /** 服务端 listen 会话约 30s 超时，对话聆听中需 stop+start 续期（与 WakeSTT 一致） */

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

    /** 唤醒词已触发、开麦交接进行中（UI 避免显示「待机」） */
    private val _isWakeHandoffActive = MutableStateFlow(false)
    val isWakeHandoffActive: StateFlow<Boolean> = _isWakeHandoffActive.asStateFlow()

    /** 说「退下」收尾后的断开重连窗口：UI 直接显示「待机」，不露出「连接中」 */
    private val _isSessionEndStandby = MutableStateFlow(false)
    val isSessionEndStandby: StateFlow<Boolean> = _isSessionEndStandby.asStateFlow()

    /** 待机时唤醒连接被服务端空闲关闭→快速重连的宽限窗口：UI 维持「待机」，不闪「连接中」 */
    private val _isStandbyReconnecting = MutableStateFlow(false)
    val isStandbyReconnecting: StateFlow<Boolean> = _isStandbyReconnecting.asStateFlow()
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

    // WebSocket 已连接但音频尚未就绪时，延后自动开麦
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
    /** 待机拍照键：连接成功后补拍 */
    private var pendingPhotoFromStandby = false
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
        viewModelScope.launch {
            sessionManager.isConnected.collect { connected ->
                _isConnected.value = connected
                if (connected) {
                    tryHandlePendingVoiceWake()
                    tryHandlePendingRecordKeyStart()
                    tryHandlePendingPhotoKey()
                    if (_state.value == ConversationState.CONNECTING) {
                        _state.value = ConversationState.IDLE
                    }
                    if (pendingAutoStart && isAutoMode) {
                        tryStartAutoConversationIfNeeded()
                    }
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
        if (result.captureOnly) {
            result.file?.let { showPhotoImage(it.absolutePath) }
            return
        }
        result.file?.let { file ->
            if (!isPhotoAlreadyShown(file.absolutePath)) {
                showPhotoImage(file.absolutePath)
            }
        }
        result.uploadResult
            .onSuccess {
                Log.i(TAG, "照片识别完成 ${result.file?.name}")
                _errorMessage.value = null
                showPhotoVisionDescription(result.visionDescription)
                XiaozhiAppEvents.endPhotoSession()
                resumeListeningAfterPhoto()
            }
            .onFailure { err ->
                XiaozhiAppEvents.endPhotoSession()
                _errorMessage.value = sanitizePhotoError(err.message)
                enterStandby("photo_failed", notifyServer = false)
            }
    }

    /** 拍照识别完成后进入自动聆听 */
    private fun resumeListeningAfterPhoto() {
        if (!conversationUiActive || !_isConnected.value) {
            Log.w(TAG, "拍照后无法开麦 ui=$conversationUiActive connected=${_isConnected.value}")
            enterStandby("photo_done_no_ui", notifyServer = false)
            return
        }
        viewModelScope.launch {
            performPhotoDoneListeningHandoff()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun performPhotoDoneListeningHandoff() {
        XiaozhiWakeForegroundService.claimMicrophoneForConversation(getApplication())
        pauseWakeListening()
        isAutoMode = true
        hasLoggedFirstAudioFrame = false

        webSocketManager.sendStopListening()
        delay(150)

        if (!conversationUiActive || !_isConnected.value) {
            isAutoMode = false
            XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
            enterStandby("photo_listen_aborted", notifyServer = false)
            return
        }
        if (!ensureRecordingReady()) {
            isAutoMode = false
            XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
            enterStandby("photo_listen_not_ready", notifyServer = false)
            return
        }
        if (!audioManager.startRecording()) {
            isAutoMode = false
            XiaozhiWakeForegroundService.releaseConversationMicrophoneClaim(getApplication())
            enterStandby("photo_record_failed", notifyServer = false)
            return
        }

        webSocketManager.sendStartListening("auto")
        transitionState(ConversationState.LISTENING, "photo_done_listen")
        updateStandbyReady()
        scheduleListeningHealthCheck()
        Log.i(TAG, "拍照完成 → 进入聆听")
        VoiceFlowLog.snapshot("photoKey.done", "listening")
    }

    private fun showPhotoImage(imagePath: String) {
        val last = _messages.value.lastOrNull()
        if (last?.role == MessageRole.USER && last.imagePath == imagePath) {
            return
        }
        addMessage(
            Message(
                role = MessageRole.USER,
                content = "",
                imagePath = imagePath,
            ),
        )
        Log.i(TAG, "展示照片 ${java.io.File(imagePath).name}")
    }

    private fun isPhotoAlreadyShown(imagePath: String): Boolean {
        val last = _messages.value.lastOrNull() ?: return false
        return last.role == MessageRole.USER && last.imagePath == imagePath
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
        ensureDownlinkPlaybackReady()
        setWakeGreetingPlaying(true)
        if (_state.value != ConversationState.SPEAKING) {
            transitionState(ConversationState.SPEAKING, "wake_greeting_tts")
        }
        Log.i(TAG, "问候 TTS start → SPEAKING（官方流程，停止上行）")
    }

    private fun completeWakeGreetingPhase(reason: String) {
        if (wakeGreetingPhaseComplete) return
        wakeGreetingPhaseComplete = true
        wakeGreetingTtsStopSeen = true
        setWakeGreetingPlaying(false)
        VoiceFlowLog.step("wake.greetingPhase", "complete reason=$reason")
        Log.d(TAG, "唤醒问候阶段结束: $reason")
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

    /** 问候 TTS 播完后再开麦；完成条件：tts stop / 音频播完 / 超时 */
    private suspend fun awaitWakeGreetingTtsEnd(timeoutMs: Long = 20_000L): Boolean {
        if (!wakeGreetingTtsStartSeen) return false
        var playbackIdleSince = 0L
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            XiaozhiWakeCoordinator.refreshHandoffTimeout(getApplication())
            if (wakeGreetingTtsStopSeen || wakeGreetingPhaseComplete) {
                Log.d(TAG, "问候 TTS 已结束（stop），开始开麦")
                return true
            }
            if (wakeGreetingAudioReceived && !audioManager.isPlaying()) {
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

    /** 下行 TTS：SPEAKING/PROCESSING 播放；LISTENING 仅在未录音或问候窗口播放 */
    private fun shouldPlayDownlinkAudio(): Boolean {
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
        // 退后台时若正在对话（聆听/处理/说话），记住状态，回前台自动恢复聆听
        val wasActiveConversation = isAutoMode &&
            (current == ConversationState.LISTENING ||
                current == ConversationState.PROCESSING ||
                current == ConversationState.SPEAKING)
        resumeManualListening = false

        cancelListenHandoff()
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
            ConversationState.LISTENING -> webSocketManager.sendStopListening()
            ConversationState.SPEAKING,
            ConversationState.PROCESSING -> webSocketManager.sendAbort("ui_pause")
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
     * - 待机 / 聆听 → 本地拍照（不向小智发送「拍照」文字）
     * - 其他状态忽略
     */
    fun onPhotoKeyPressed() {
        if (!XiaozhiAppEvents.consumePhotoKeyPressEvent()) {
            Log.d(TAG, "拍照键：重复事件忽略")
            return
        }
        if (!conversationUiActive) {
            Log.d(TAG, "拍照键：对话页未就绪")
            pendingPhotoFromStandby = true
            return
        }
        if (XiaozhiAppEvents.isPhotoSessionActive()) {
            Log.d(TAG, "拍照键：拍照会话进行中，忽略")
            return
        }
        when (_state.value) {
            ConversationState.IDLE -> sendPhotoWakeFromStandby()
            ConversationState.LISTENING -> sendPhotoTextInConversation()
            else -> Log.d(TAG, "拍照键：仅待机/聆听可用，当前=${_state.value}")
        }
    }

    private fun tryHandlePendingPhotoKey() {
        if (!pendingPhotoFromStandby) return
        if (!conversationUiActive || !_isConnected.value) return
        if (_state.value != ConversationState.IDLE) {
            pendingPhotoFromStandby = false
            return
        }
        pendingPhotoFromStandby = false
        executePhotoWakeDetect()
    }

    private fun sendPhotoWakeFromStandby() {
        if (_state.value != ConversationState.IDLE) {
            Log.d(TAG, "待机拍照：非 IDLE state=${_state.value}")
            return
        }
        if (pendingSessionEnd || pendingVoiceWake || isWakeHandoffInProgress()) {
            Log.d(TAG, "待机拍照：交接/结束语中，忽略")
            return
        }
        if (!_isConnected.value) {
            pendingPhotoFromStandby = true
            connect()
            Log.d(TAG, "待机拍照：等待 WebSocket 连接")
            return
        }
        executePhotoWakeDetect()
    }

    private fun executePhotoWakeDetect() {
        if (_state.value != ConversationState.IDLE || !_isConnected.value) return
        pauseWakeListening()
        webSocketManager.sendStopListening()
        pendingAutoStart = false
        transitionState(ConversationState.PROCESSING, "photo_wake_detect")
        // 按文档：待机下拍照键 → 发「拍照」唤醒词给服务端，由服务端调 MCP take_photo
        hideNextSttEcho = true
        webSocketManager.sendWakeWordDetected("拍照")
        Log.i(TAG, "待机拍照键：发送「拍照」detect 给服务端，等待 MCP take_photo")
        VoiceFlowLog.snapshot("photoKey.standby", "send 拍照 detect")
    }

    private fun sendPhotoTextInConversation() {
        if (_state.value != ConversationState.LISTENING || !_isConnected.value) {
            Log.d(TAG, "聆听拍照：状态不可用 state=${_state.value} connected=${_isConnected.value}")
            return
        }
        if (pendingSessionEnd) {
            Log.d(TAG, "聆听拍照：结束语等待中，忽略")
            return
        }
        audioManager.stopRecording()
        webSocketManager.sendStopListening()
        pendingAutoStart = false
        transitionState(ConversationState.PROCESSING, "photo_key_listening")
        // 按文档：对话中拍照键 → 直接发「拍照」文字给服务端，由服务端调 MCP take_photo
        hideNextSttEcho = true
        webSocketManager.sendWakeWordDetected("拍照")
        Log.i(TAG, "聆听拍照键：发送「拍照」文字给服务端，等待 MCP take_photo")
        VoiceFlowLog.snapshot("photoKey.listening", "send 拍照 text")
    }

    private fun stopConversationFromVoiceKey() {
        cancelListenHandoff()
        pendingRecordKeyStart = false
        pendingVoiceWake = false
        pendingAutoStart = false
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
            webSocketManager.sendStopListening()
            webSocketManager.sendAbort("voice_key_stop")
        }
        stopListeningKeepalive()
        _state.value = ConversationState.IDLE
        Log.i(TAG, "录音键：结束对话 → 待机")
        prepareStandbyWakeListening()
    }

    /** 录音键在「处理中/说话中」按下：打断当前回复，重新进入聆听 */
    @SuppressLint("MissingPermission")
    private fun restartListeningFromVoiceKey() {
        cancelSpeakingWatchdog()
        cancelSessionEndFallback()
        cancelSessionEndStandby()
        pendingSessionEnd = false
        sessionEndAudioReceived = false
        sessionEndTtsStopSeen = false
        sessionEndReconnectPending = false
        audioManager.stopPlaying()
        if (_isConnected.value) {
            webSocketManager.sendAbort("voice_key_interrupt")
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
        Log.i(TAG, "onVoiceWakeDetected 关键词=${WakePhraseMatcher.WAKE_PHRASE}")
        logFlow("wake.detected", "关键词=${WakePhraseMatcher.WAKE_PHRASE}")
        _isSessionEndStandby.value = false
        wakeConversationHandoff = true
        isAutoMode = true
        pendingVoiceWake = true
        pendingRecordKeyStart = false
        pendingWakeRetryCount = 0
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
            VoiceFlowLog.decision("wake.pending", "开麦", false, "WebSocket 未连接")
            Log.d(TAG, "pendingWake: WebSocket 未连接")
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
                webSocketManager.sendAbort("wake_handoff")
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
        if (XiaozhiWakeCoordinator.hasServerGreetingTtsPending() || wakeGreetingTtsStartSeen) {
            XiaozhiWakeCoordinator.clearServerGreetingTtsPending()
            if (!wakeGreetingTtsStartSeen) {
                markWakeGreetingTtsStart()
            }
            Log.i(TAG, "pendingWake → 沿用 WakeSTT 触发的问候 TTS，不发送 detect")
            logFlow("wake.detect.skip", "server_stt_greeting")
        } else {
            Log.i(TAG, "pendingWake → 等待 WakeSTT 触发的问候 TTS，不发送 detect")
            logFlow("wake.detect.skip", "await_server_greeting")
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

    private fun shouldSuppressWakeHandoffEcho(): Boolean =
        isWakeGreetingWindow() ||
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

    private fun shouldAcceptWakeGreetingTtsStop(): Boolean =
        wakeGreetingAudioReceived ||
            audioManager.isPlaying() ||
            (wakeGreetingTtsStartSeen && isWakeGreetingWindow())

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
            Log.w(TAG, "结束语等待超时，强制断开重连")
            Log.w(SESSION_END_TAG, "20s 内未收到完整结束语，强制断开重连")
            scheduleSessionEndCompletion("session_end_timeout")
        }
    }

    /** 结束语 tts stop 后等待 AudioTrack 播完，再关闭任务并断开重连 */
    private fun scheduleSessionEndCompletion(trigger: String) {
        if (sessionEndStandbyJob?.isActive == true) return
        sessionEndStandbyJob = viewModelScope.launch {
            cancelSessionEndFallback()
            logFlow("sessionEnd.awaitPlayback", "trigger=$trigger play=${audioManager.isPlaying()}")
            Log.i(SESSION_END_TAG, "等待结束语播完 trigger=$trigger")
            awaitSessionEndPlayback()
            if (!pendingSessionEnd && trigger != "session_end_timeout") return@launch
            finalizeSessionEndAndReconnect(trigger)
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
        Log.w(TAG, "结束语播放等待超时，仍执行断开重连")
    }

    /** 关闭对话相关任务，断开 WebSocket 并立即重连，恢复唤醒待机 */
    private fun finalizeSessionEndAndReconnect(trigger: String) {
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
        transitionState(ConversationState.IDLE, "session_end:$trigger")
        Log.i(TAG, "结束语完成($trigger) → 关闭任务并断开重连")
        Log.i(SESSION_END_TAG, "结束语播完，断开 WebSocket 并重连")
        logFlow("sessionEnd.complete", "trigger=$trigger")
        // 退下收尾的断开重连属内部动作，UI 直接显示「待机」，不露出「连接中」
        _isSessionEndStandby.value = true
        viewModelScope.launch {
            delay(8_000)
            _isSessionEndStandby.value = false
        }
        sessionManager.disconnect()
        sessionEndReconnectPending = true
        _isAwaitingReconnect.value = true
        transitionState(ConversationState.CONNECTING, "session_end_reconnect")
        sessionManager.ensureConnected()
        sessionEndStandbyJob = null
    }

    private fun shutdownAllConversationTasks() {
        cancelListenHandoff()
        cancelSpeakingWatchdog()
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

    private fun scheduleSpeakingWatchdog() {
        if (isWakeGreetingTurn() || _isWakeGreetingPlaying.value ||
            listenHandoffJob?.isActive == true || pendingSessionEnd
        ) {
            VoiceFlowLog.step("tts.watchdog", "跳过（唤醒问候/交接中）")
            return
        }
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
        if (isAutoMode && _isConnected.value && conversationUiActive &&
            (_state.value == ConversationState.SPEAKING ||
                _state.value == ConversationState.LISTENING ||
                _state.value == ConversationState.PROCESSING ||
                pendingVoiceWake ||
                wakeConversationHandoff ||
                XiaozhiWakeCoordinator.isWakeHandoffInProgress() ||
                listenHandoffJob?.isActive == true)
        ) {
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
        // 聆听中被服务端断开 = 无语音输入超时（需求：进入待机，不续聊）；
        // 仅在回复在途（PROCESSING/SPEAKING）断线时才重连恢复
        val replyInFlight = _state.value == ConversationState.PROCESSING ||
            _state.value == ConversationState.SPEAKING
        return replyInFlight && isAutoMode
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
        cancelSessionEndStandby()
        stopListeningKeepalive()
        pendingSessionEnd = false
        sessionEndAudioReceived = false
        sessionEndTtsStopSeen = false
        sessionEndReconnectPending = false
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
                standbyReconnectGraceJob?.cancel()
                _isStandbyReconnecting.value = false
                if (_state.value == ConversationState.CONNECTING) {
                    _state.value = ConversationState.IDLE
                }
                if (sessionEndReconnectPending) {
                    sessionEndReconnectPending = false
                    _isSessionEndStandby.value = false
                    Log.i(TAG, "退下重连完成 → 恢复唤醒待机")
                    logFlow("sessionEnd.reconnected", "session=${webSocketManager.getSessionId()}")
                    prepareStandbyWakeListening()
                    return
                }
                tryHandlePendingVoiceWake()
                tryHandlePendingRecordKeyStart()
                tryHandlePendingPhotoKey()
                if (pendingAutoStart) {
                    initializeAudio()
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
                    // 待机时唤醒连接常被服务端空闲关闭、随即快速重连。
                    // 宽限期内 UI 维持「待机」（不切 CONNECTING、不闪「连接中」）；
                    // 超过宽限仍未连上，才显示「连接中」。
                    _isStandbyReconnecting.value = true
                    standbyReconnectGraceJob?.cancel()
                    standbyReconnectGraceJob = viewModelScope.launch {
                        delay(STANDBY_RECONNECT_GRACE_MS)
                        if (!_isConnected.value) {
                            _isStandbyReconnecting.value = false
                            _isStandbyReady.value = false
                            if (_state.value == ConversationState.IDLE) {
                                transitionState(ConversationState.CONNECTING, "ws_disconnect_reconnect_slow")
                            }
                            updateStandbyReady()
                        }
                    }
                    Log.i(TAG, "WS 断开 → 待机快速重连宽限中（UI 维持待机）")
                    VoiceFlowLog.warn(
                        "ws.disconnected",
                        "待机中断连，宽限重连 | blockers=${standbyReadyBlockers().joinToString(",")}",
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
                        Log.i(TAG, "隐藏短指令 STT 回显: $text")
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
                            // 结束语播放：设备此刻在出声，切到 SPEAKING 让 UI 显示「说话中」而非「思考中」
                            if (pendingSessionEnd && _state.value == ConversationState.PROCESSING) {
                                transitionState(ConversationState.SPEAKING, "session_end_farewell")
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
                                VoiceFlowLog.decision(
                                    "msg.tts.start",
                                    "→SPEAKING",
                                    false,
                                    "已在聆听，忽略迟来问候",
                                )
                                Log.d(TAG, "已在聆听，忽略迟来 TTS start")
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
                            transitionState(ConversationState.SPEAKING, "tts_start")
                            scheduleSpeakingWatchdog()
                            VoiceFlowLog.decision("msg.tts.start", "→SPEAKING", true, flowContext())
                            Log.d(TAG, "TTS start → SPEAKING")
                        }
                        "stop" -> {
                            if (_state.value == ConversationState.SPEAKING &&
                                isWakeGreetingTurn() &&
                                !pendingSessionEnd
                            ) {
                                if (!shouldAcceptWakeGreetingTtsStop()) {
                                    Log.d(TAG, "忽略未开始播放的问候 TTS stop")
                                    return@handleTextMessage
                                }
                                wakeGreetingTtsStopSeen = true
                                setWakeGreetingPlaying(false)
                                wakeGreetingAudioReceived = false
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
                                    wakeGreetingAudioReceived = false
                                    completeWakeGreetingPhase("idle_tts_stop")
                                    Log.d(TAG, "唤醒交接/问候窗口内 IDLE TTS stop")
                                } else {
                                    Log.d(TAG, "唤醒交接/问候窗口内忽略 IDLE TTS stop（尚未开始播放）")
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
        if (shouldArmWakeGreetingFromServerTts() && conversationUiActive) {
            armWakeGreetingFromServerTtsIfNeeded()
            if (!wakeGreetingTtsStartSeen) {
                markWakeGreetingTtsStart()
            }
        }
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
            sessionEndAudioReceived = true
            logSessionEndServerReply("binary", "tts_audio ${data.size} bytes")
        }
        if (isWakeGreetingTurn() && _state.value != ConversationState.CONNECTING) {
            wakeGreetingAudioReceived = true
            setWakeGreetingPlaying(true)
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
                if (!hasLoggedFirstAudioFrame) {
                    hasLoggedFirstAudioFrame = true
                    Log.d(TAG, "首帧音频上行 ${event.data.size}B")
                }
                webSocketManager.sendBinaryMessage(event.data)
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
            Log.d(TAG, "已在自动聆听，跳过")
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
        XiaozhiWakeForegroundService.claimMicrophoneForConversation(getApplication())
        XiaozhiWakeCoordinator.refreshHandoffTimeout(getApplication())
        pauseWakeListening()

        isAutoMode = true
        hasLoggedFirstAudioFrame = false
        if (isWakeGreetingWindow()) {
            Log.i(TAG, "唤醒问候：官方流程 SPEAKING 播完再 listen/start + 开麦")
            VoiceFlowLog.step("handoff", "await wake greeting SPEAKING")
            if (!awaitWakeGreetingTtsStart()) {
                webSocketManager.sendWakeWordDetected(WakePhraseMatcher.WAKE_PHRASE)
                Log.w(TAG, "WakeSTT 未收到问候 TTS start，fallback 发送 detect")
                VoiceFlowLog.warn("wake.detect.fallback", "no_server_greeting_tts")
                awaitWakeGreetingTtsStart(timeoutMs = 5_000L)
            }
            awaitWakeGreetingTtsEnd()
            cancelSpeakingWatchdog()
            audioManager.stopPlaying()
            if (_state.value == ConversationState.SPEAKING) {
                transitionState(ConversationState.IDLE, "wake_greeting_handoff")
            }
            webSocketManager.sendStopListening()
            VoiceFlowLog.step("handoff", "greeting done sendStopListening")
            delay(150)
        } else {
            webSocketManager.sendStopListening()
            VoiceFlowLog.step("handoff", "sendStopListening + delay 150ms")
            delay(150)
        }

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
        wakeGreetingListenActive = false
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
        updateWakeHandoffUi()
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
    private fun shouldStartNewAssistantBubble(): Boolean {
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
        val currentMessages = _messages.value.toMutableList()
        if (currentMessages.isNotEmpty() &&
            currentMessages.last().role == MessageRole.ASSISTANT &&
            !shouldStartNewAssistantBubble()
        ) {
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
        if (currentMessages.isNotEmpty() &&
            currentMessages.last().role == MessageRole.ASSISTANT &&
            !shouldStartNewAssistantBubble()
        ) {
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