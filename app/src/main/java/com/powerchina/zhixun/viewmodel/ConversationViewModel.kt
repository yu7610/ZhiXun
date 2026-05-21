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
import com.powerchina.zhixun.xiaozhi.XiaozhiSessionManager
import com.powerchina.zhixun.xiaozhi.wake.WakePhraseMatcher
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeCoordinator
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import kotlinx.coroutines.Job
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

    // 语音唤醒「你好」后待进入对话
    private var pendingVoiceWake = false

    private var pendingWakeRetryJob: Job? = null
    private var pendingWakeRetryCount = 0

    init {
        startEventListening()
        viewModelScope.launch {
            sessionManager.isConnected.collect { _isConnected.value = it }
        }
        viewModelScope.launch {
            sessionManager.isConnecting.collect { connecting ->
                if (connecting) _state.value = ConversationState.CONNECTING
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
            if (pendingVoiceWake) {
                initializeAudio()
            }
            tryHandlePendingVoiceWake()
        }
    }

    private fun pauseConversationForUi() {
        val wakeHandoff = pendingVoiceWake || XiaozhiWakeCoordinator.isWakeHandoffInProgress()
        if (wakeHandoff) {
            Log.d(TAG, "唤醒交接中，跳过 UI 暂停清理")
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
     * 检测到「你好」后：连接小智并进入自动对话。
     */
    fun onVoiceWakeDetected() {
        Log.i(TAG, "onVoiceWakeDetected 关键词=${WakePhraseMatcher.WAKE_PHRASE}")
        isAutoMode = true
        pendingVoiceWake = true
        pendingWakeRetryCount = 0
        shouldResumeOnUiReturn = false
        resumeManualListening = false
        audioManager.stopPlaying()
        audioManager.stopRecording()
        webSocketManager.sendAbort("wake_handoff")
        _state.value = ConversationState.IDLE
        initializeAudio()
        connect()
        tryHandlePendingVoiceWake()
    }

    private fun tryHandlePendingVoiceWake() {
        if (!pendingVoiceWake) return
        if (!conversationUiActive) {
            Log.d(TAG, "pendingWake: 对话页未就绪")
            return
        }
        if (!_isConnected.value) {
            Log.d(TAG, "pendingWake: WebSocket 未连接")
            return
        }
        if (_state.value != ConversationState.IDLE) {
            Log.d(TAG, "pendingWake: 状态=${_state.value}，重置为 IDLE")
            audioManager.stopPlaying()
            audioManager.stopRecording()
            webSocketManager.sendAbort("wake_handoff")
            _state.value = ConversationState.IDLE
        }
        if (!ensureAudioReadyForPendingWake()) {
            return
        }

        pendingVoiceWake = false
        pendingWakeRetryJob?.cancel()
        pendingWakeRetryJob = null
        pendingWakeRetryCount = 0
        pauseWakeListening()
        webSocketManager.sendWakeWordDetected(WakePhraseMatcher.WAKE_PHRASE)
        Log.i(TAG, "pendingWake → 发送 detect + 开始自动对话")
        XiaozhiWakeCoordinator.clearWakeHandoff("conversation_started")
        startAutoConversation()
    }

    private fun pauseWakeListening() {
        XiaozhiWakeForegroundService.pauseListening(getApplication())
    }

    private fun resumeWakeListeningIfNeeded() {
        if (XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
            Log.d(TAG, "唤醒交接中，不恢复后台监听")
            return
        }
        if (_state.value != ConversationState.LISTENING && !pendingVoiceWake) {
            Log.d(TAG, "恢复语音唤醒监听 state=${_state.value}")
            XiaozhiWakeForegroundService.ensureListeningActive(getApplication())
        }
    }

    /**
     * 初始化音频服务（在获得权限后调用）
     */
    @SuppressLint("MissingPermission")
    fun initializeAudio() {
        if (isAudioInitialized && audioManager.isReady()) {
            _errorMessage.value = null
            tryHandlePendingVoiceWake()
            if (!pendingVoiceWake) {
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
                _isConnected.value = true
                _errorMessage.value = null
                if (_state.value == ConversationState.CONNECTING) {
                    _state.value = ConversationState.IDLE
                }
                tryHandlePendingVoiceWake()
                tryStartAutoConversationIfNeeded()
            }

            is WebSocketEvent.Disconnected -> {
                Log.w(TAG, "WS Disconnected state=${_state.value}")
                _isConnected.value = false
                audioManager.stopRecording()
                audioManager.stopPlaying()
            }

            is WebSocketEvent.TextMessage -> {
                handleTextMessage(event.message)
            }

            is WebSocketEvent.BinaryMessage -> {
                handleBinaryMessage(event.data)
            }
            
            is WebSocketEvent.MCPMessage -> {
                handleMCPMessage(event.message)
            }

            is WebSocketEvent.Error -> {
                Log.e(TAG, "WebSocket错误: ${event.error}")
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
        return pendingVoiceWake ||
            XiaozhiWakeCoordinator.isWakeHandoffInProgress() ||
            XiaozhiWakeForegroundService.isWakeListeningActive()
    }

    /**
     * 处理文本消息
     */
    private fun handleTextMessage(message: String) {
        try {
            if (shouldIgnoreConversationServerMessages()) {
                Log.v(TAG, "忽略唤醒阶段消息")
                return
            }
            val json = gson.fromJson(message, JsonObject::class.java)
            val type = json.get("type")?.asString
            val sessionId = json.get("session_id")?.asString

            Log.d(TAG, "消息 type=$type session=$sessionId state=${_state.value}")

            when (type) {
                "stt" -> {
                    if (!conversationUiActive) return@handleTextMessage
                    val text = json.get("text")?.asString
                    Log.d(TAG, "STT: $text")
                    if (!text.isNullOrEmpty() &&
                        WakePhraseMatcher.matches(text) &&
                        _state.value != ConversationState.LISTENING
                    ) {
                        Log.d(TAG, "忽略唤醒词 STT")
                        return@handleTextMessage
                    }
                    if (!text.isNullOrEmpty() && !text.contains("请登录控制面板")) {
                        currentUserMessage = text
                        addMessage(Message(
                            role = MessageRole.USER,
                            content = text
                        ))
                        audioManager.stopRecording()
                        _state.value = ConversationState.PROCESSING
                    }
                }
                
                "llm" -> {
                    // 大语言模型结果
                    val emotion = json.get("emotion")?.asString
                    val text = json.get("text")?.asString
                    Log.d(TAG, "LLM emotion=$emotion text=$text")
                    if (!text.isNullOrEmpty()) {
                        updateAssistantMessage(text)
                    }
                }
                
                "tts" -> {
                    val state = json.get("state")?.asString
                    when (state) {
                        "sentence_start" -> {
                            // TTS句子开始，显示要播放的文本
                            val text = json.get("text")?.asString
                            if (!text.isNullOrEmpty()) {
                                updateAssistantMessage(text)
                            }
                        }
                        "sentence_end" -> {
                            // TTS句子结束，有时包含完整的句子内容
                            val text = json.get("text")?.asString
                            if (!text.isNullOrEmpty()) {
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
                            val canPlayTts = _state.value == ConversationState.PROCESSING ||
                                _state.value == ConversationState.SPEAKING ||
                                (_state.value == ConversationState.LISTENING && isAutoMode)
                            if (!canPlayTts) {
                                Log.d(TAG, "忽略非对话中的 TTS start")
                                return@handleTextMessage
                            }
                            _state.value = ConversationState.SPEAKING
                            Log.d(TAG, "TTS start → SPEAKING")
                        }
                        "stop" -> {
                            audioManager.stopPlaying()
                            Log.d(TAG, "TTS stop")
                            if (!conversationUiActive) {
                                if (isAutoMode) shouldResumeOnUiReturn = true
                                return@handleTextMessage
                            }
                            if (isAutoMode && _state.value == ConversationState.SPEAKING) {
                                startNextRound()
                            } else if (!isAutoMode) {
                                _state.value = ConversationState.IDLE
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析文本消息失败", e)
        }
    }

    /**
     * 处理二进制消息（音频数据）
     */
    private fun handleBinaryMessage(data: ByteArray) {
        if (!conversationUiActive || shouldIgnoreConversationServerMessages()) return
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
    fun startAutoConversation() {
        if (!conversationUiActive) {
            pendingAutoStart = true
            return
        }
        if (_state.value == ConversationState.LISTENING && isAutoMode) {
            Log.d(TAG, "已在自动聆听，跳过")
            return
        }
        if (_state.value != ConversationState.IDLE || !_isConnected.value) {
            return
        }
        if (!ensureRecordingReady()) {
            pendingAutoStart = true
            return
        }

        isAutoMode = true
        _state.value = ConversationState.LISTENING
        if (!audioManager.startRecording()) {
            isAutoMode = false
            _state.value = ConversationState.IDLE
            return
        }

        webSocketManager.sendStartListening("auto")
        pauseWakeListening()
        Log.i(TAG, "开始自动对话 mode=auto")
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
     * 开始下一轮对话（自动模式）
     */
    @SuppressLint("MissingPermission")
    private fun startNextRound() {
        if (!conversationUiActive) {
            shouldResumeOnUiReturn = isAutoMode
            return
        }
        if (!isAutoMode || !_isConnected.value) {
            _state.value = ConversationState.IDLE
            return
        }
        if (!ensureRecordingReady()) {
            _state.value = ConversationState.IDLE
            isAutoMode = false
            return
        }

        _state.value = ConversationState.LISTENING
        if (!audioManager.startRecording()) {
            _state.value = ConversationState.IDLE
            isAutoMode = false
            return
        }

        webSocketManager.sendStartListening("auto")
        pauseWakeListening()
        Log.d(TAG, "下一轮自动对话")
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
        audioManager.stopPlaying()
        audioManager.stopRecording()
        
        // 发送中断消息
        webSocketManager.sendAbort("user_interrupt")
        
        // 退出自动模式
        isAutoMode = false
        _state.value = ConversationState.IDLE
        resumeWakeListeningIfNeeded()
        Log.i(TAG, "用户打断对话")
    }

    /**
     * 断开连接并停止所有操作
     */
    fun disconnect() {
        Log.i(TAG, "断开连接")
        audioManager.stopRecording()
        audioManager.stopPlaying()
        sessionManager.disconnect()
        _state.value = ConversationState.IDLE
        isAutoMode = false
        resumeWakeListeningIfNeeded()
    }

    /**
     * 更新助手消息（用于流式输出）
     */
    private fun updateAssistantMessage(text: String) {
        val currentMessages = _messages.value.toMutableList()
        if (currentMessages.isNotEmpty() && currentMessages.last().role == MessageRole.ASSISTANT) {
            val lastMessage = currentMessages.last()
            // 如果最后一条消息是助手发的，则追加内容
            currentMessages[currentMessages.size - 1] = lastMessage.copy(
                content = lastMessage.content + text
            )
            _messages.value = currentMessages
        } else {
            // 否则新增一条助手消息
            addMessage(Message(
                role = MessageRole.ASSISTANT,
                content = text
            ))
        }
    }

    /**
     * 同步助手消息（用于确保 sentence_end 的完整性）
     */
    private fun syncAssistantMessage(text: String) {
        val currentMessages = _messages.value.toMutableList()
        if (currentMessages.isNotEmpty() && currentMessages.last().role == MessageRole.ASSISTANT) {
            val lastMessage = currentMessages.last()
            // 如果最后一条消息长度小于新收到的完整文本，则更新它
            if (lastMessage.content.length < text.length) {
                currentMessages[currentMessages.size - 1] = lastMessage.copy(content = text)
                _messages.value = currentMessages
            }
        } else {
            addMessage(Message(role = MessageRole.ASSISTANT, content = text))
        }
    }

    /**
     * 停止自动对话模式
     */
    fun stopAutoConversation() {
        isAutoMode = false
        audioManager.stopRecording()
        audioManager.stopPlaying()
        
        // 发送中断消息
        webSocketManager.sendAbort("stop_auto_mode")
        
        _state.value = ConversationState.IDLE
        Log.d(TAG, "停止自动对话模式")
        resumeWakeListeningIfNeeded()
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

    /**
     * 处理MCP消息
     */
    private fun handleMCPMessage(message: String) {
        Log.d(TAG, "MCP: $message")
        
        // 添加MCP消息到对话列表（可选）
        addMessage(Message(
            role = MessageRole.SYSTEM,
            content = "MCP: $message"
        ))
    }

    override fun onCleared() {
        super.onCleared()
        pendingWakeRetryJob?.cancel()
        pendingWakeRetryJob = null
        isAudioInitialized = false
        pendingAutoStart = false
        audioManager.cleanup()
    }
}