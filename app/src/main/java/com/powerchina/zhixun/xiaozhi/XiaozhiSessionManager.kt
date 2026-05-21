package com.powerchina.zhixun.xiaozhi

import android.app.Application
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.data.XiaozhiConfig
import com.powerchina.zhixun.network.OtaService
import com.powerchina.zhixun.network.WebSocketEvent
import com.powerchina.zhixun.network.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 小智后台会话：应用启动后自动 OTA + WebSocket 连接，与 UI 生命周期解耦。
 */
class XiaozhiSessionManager private constructor(
    private val application: Application,
) {
    val webSocketManager: WebSocketManager = WebSocketManager(application)
    private val otaService = OtaService(application)
    private val configManager = ConfigManager(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectMutex = Mutex()

    private var config: XiaozhiConfig = configManager.loadConfig()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _activationCode = MutableStateFlow<String?>(null)
    val activationCode: StateFlow<String?> = _activationCode.asStateFlow()

    private val _awaitingActivation = MutableStateFlow(false)
    val awaitingActivation: StateFlow<Boolean> = _awaitingActivation.asStateFlow()

    init {
        scope.launch {
            webSocketManager.events.collect { event ->
                when (event) {
                    is WebSocketEvent.Connected -> {
                        _isConnected.value = true
                        _isConnecting.value = false
                        _lastError.value = null
                        XiaozhiLog.i(MODULE, "WebSocket Connected")
                    }
                    is WebSocketEvent.Disconnected -> {
                        _isConnected.value = false
                        _isConnecting.value = false
                        XiaozhiLog.i(MODULE, "WebSocket Disconnected")
                    }
                    is WebSocketEvent.Error -> {
                        _isConnecting.value = false
                        _lastError.value = event.error
                        XiaozhiLog.e(MODULE, "WebSocket Error: ${event.error}")
                    }
                    else -> Unit
                }
            }
        }
    }

    fun reloadConfig() {
        config = configManager.loadConfig()
    }

    fun updateConfig(newConfig: XiaozhiConfig) {
        val old = config
        config = newConfig
        configManager.saveConfig(newConfig)
        if (old.websocketUrl != newConfig.websocketUrl ||
            old.macAddress != newConfig.macAddress ||
            old.token != newConfig.token ||
            old.otaUrl != newConfig.otaUrl
        ) {
            XiaozhiLog.i(MODULE, "配置变更，重新连接")
            disconnect()
            ensureConnected()
        }
    }

    /**
     * 若未连接则执行 OTA（如需）并建立 WebSocket。可在应用启动、保存设置后调用。
     */
    fun ensureConnected() {
        webSocketManager.enableReconnect()
        if (webSocketManager.isConnected()) {
            _isConnected.value = true
            XiaozhiLog.d(MODULE, "ensureConnected: 已连接，跳过")
            return
        }
        if (_awaitingActivation.value) {
            XiaozhiLog.d(MODULE, "ensureConnected: 等待激活，跳过")
            return
        }
        if (!isNetworkConfigReady()) {
            XiaozhiLog.w(MODULE, "ensureConnected: OTA/WSS/MAC/Token 未配置完整")
            return
        }

        XiaozhiLog.d(MODULE, "ensureConnected: 排队连接任务")
        scope.launch {
            connectMutex.withLock {
                if (webSocketManager.isConnected()) {
                    XiaozhiLog.d(MODULE, "connectMutex: 已连接，跳过")
                    return@withLock
                }
                if (_isConnecting.value) {
                    XiaozhiLog.d(MODULE, "connectMutex: 连接进行中，跳过")
                    return@withLock
                }
                _isConnecting.value = true
                _lastError.value = null
                XiaozhiLog.i(MODULE, "开始 OTA + WebSocket 连接")
                try {
                    performOtaAndConnect()
                } finally {
                    _isConnecting.value = false
                }
            }
        }
    }

    fun onActivationConfirmed() {
        _awaitingActivation.value = false
        _activationCode.value = null
        ensureConnected()
    }

    fun dismissActivation() {
        _awaitingActivation.value = false
        _activationCode.value = null
    }

    fun disconnect() {
        webSocketManager.disconnect()
        _isConnected.value = false
        _isConnecting.value = false
    }

    /** 应用关闭：断开 WebSocket 并禁止自动重连 */
    fun shutdown() {
        webSocketManager.disableReconnect()
        disconnect()
        _lastError.value = null
        Log.i(TAG, "小智会话已关闭")
    }

    private fun isNetworkConfigReady(): Boolean {
        val hasEndpoint = config.otaUrl.isNotBlank() || config.websocketUrl.isNotBlank()
        return hasEndpoint && config.macAddress.isNotBlank() && config.token.isNotBlank()
    }

    private suspend fun performOtaAndConnect() {
        if (config.otaUrl.isNotBlank()) {
            Log.d(TAG, "自动 OTA 检查...")
            val result = otaService.reportDeviceAndGetOta(
                clientId = config.uuid,
                deviceId = config.macAddress,
                otaUrl = config.otaUrl,
            )
            result.onSuccess { otaResponse ->
                if (otaResponse.websocket.url.isNotBlank()) {
                    config = config.copy(websocketUrl = otaResponse.websocket.url)
                    configManager.saveConfig(config)
                }
                otaResponse.activation?.let { activation ->
                    Log.i(TAG, "需要激活: ${activation.code}")
                    _activationCode.value = activation.code
                    _awaitingActivation.value = true
                    _isConnecting.value = false
                    return
                }
                openWebSocket()
            }.onFailure { e ->
                Log.e(TAG, "OTA 失败，尝试直接连接", e)
                _lastError.value = "OTA失败: ${e.message}"
                openWebSocket()
            }
        } else {
            openWebSocket()
        }
    }

    private suspend fun openWebSocket() {
        val url = config.websocketUrl
        if (url.isBlank()) {
            Log.e(TAG, "WebSocket URL 为空")
            _lastError.value = "WebSocket 地址未配置"
            return
        }
        Log.d(TAG, "连接 WebSocket: $url")
        webSocketManager.connect(
            url = url,
            deviceId = config.macAddress,
            token = config.token,
        )
        val connected = withTimeoutOrNull(20_000) {
            while (!webSocketManager.isConnected()) {
                delay(100)
            }
            true
        } ?: false
        if (connected) {
            _isConnected.value = true
            _isConnecting.value = false
            _lastError.value = null
            Log.i(TAG, "WebSocket 握手完成")
        } else {
            Log.e(TAG, "WebSocket 握手超时")
            _lastError.value = "WebSocket 握手超时"
        }
    }

    companion object {
        private const val MODULE = "Session"

        @Volatile
        private var instance: XiaozhiSessionManager? = null

        fun getInstance(application: Application): XiaozhiSessionManager {
            return instance ?: synchronized(this) {
                instance ?: XiaozhiSessionManager(application).also { instance = it }
            }
        }
    }
}
