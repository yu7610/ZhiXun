package com.powerchina.zhixun.xiaozhi

import android.app.Application
import android.util.Log
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.data.MqttConfig
import com.powerchina.zhixun.data.XiaozhiConfig
import com.powerchina.zhixun.network.MqttUdpManager
import com.powerchina.zhixun.network.OtaService
import com.powerchina.zhixun.network.MqttUdpEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 小智后台会话：应用启动后自动 OTA + MQTT/UDP 连接，与 UI 生命周期解耦。
 */
class XiaozhiSessionManager private constructor(
    private val application: Application,
) {
    /** MQTT 控制通道 + UDP 音频 */
    val mqttManager: MqttUdpManager = MqttUdpManager(application)
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

    /** 用户主动断开后保持待机，直到唤醒/拍照/录音/手动连接再恢复 */
    @Volatile
    private var userStandbyDisconnected = false

    init {
        scope.launch {
            mqttManager.events.collect { event ->
                when (event) {
                    is MqttUdpEvent.Connected -> {
                        _isConnected.value = true
                        _isConnecting.value = false
                        _lastError.value = null
                        Log.i(TAG, "MQTT/UDP Connected")
                    }
                    is MqttUdpEvent.Disconnected -> {
                        _isConnected.value = false
                        _isConnecting.value = false
                        XiaozhiVisionRegistry.clear()
                        Log.i(TAG, "MQTT/UDP Disconnected，重连由业务层按场景决定")
                    }
                    is MqttUdpEvent.Error -> {
                        _isConnecting.value = false
                        _lastError.value = event.error
                        Log.e(TAG, "MQTT/UDP Error: ${event.error}")
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
        if (old.mqtt != newConfig.mqtt ||
            old.macAddress != newConfig.macAddress ||
            old.token != newConfig.token ||
            old.otaUrl != newConfig.otaUrl
        ) {
            Log.i(TAG, "配置变更，重新连接")
            clearUserStandbyDisconnect()
            disconnect()
            ensureConnected()
        }
    }

    /**
     * 若未连接则执行 OTA（如需）并建立 MQTT/UDP。可在应用启动、保存设置后调用。
     * 用户主动断开后待机期间会自动跳过，除非 [clearUserStandbyDisconnect] 或显式 [connect]。
     */
    fun ensureConnected() {
        if (userStandbyDisconnected) {
            Log.d(TAG, "ensureConnected: 用户主动断开后待机，跳过")
            return
        }
        mqttManager.enableReconnect()
        if (mqttManager.isConnected()) {
            _isConnected.value = true
            Log.d(TAG, "ensureConnected: 已连接，跳过")
            return
        }
        if (_awaitingActivation.value) {
            Log.d(TAG, "ensureConnected: 等待激活，跳过")
            return
        }
        if (!isNetworkConfigReady()) {
            Log.w(TAG, "ensureConnected: OTA/MQTT/MAC 未配置完整")
            return
        }

        Log.d(TAG, "ensureConnected: 排队连接任务")
        scope.launch {
            connectMutex.withLock {
                if (mqttManager.isConnected()) {
                    Log.d(TAG, "connectMutex: 已连接，跳过")
                    return@withLock
                }
                if (_isConnecting.value) {
                    Log.d(TAG, "connectMutex: 连接进行中，跳过")
                    return@withLock
                }
                _isConnecting.value = true
                _lastError.value = null
                Log.i(TAG, "开始 OTA + MQTT/UDP 连接")
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

    /** 关闭当前连接但保留参数，便于立即重连（配置刷新等场景） */
    fun disconnect() {
        mqttManager.disconnect(
            disableAutoReconnect = false,
            clearCredentials = false,
        )
        _isConnected.value = false
        _isConnecting.value = false
    }

    /**
     * 用户主动断开：禁止自动重连，保留配置，待机直至用户再次唤醒/按键/手动连接。
     */
    fun disconnectForUserStandby() {
        userStandbyDisconnected = true
        mqttManager.disableReconnect()
        mqttManager.disconnect(
            disableAutoReconnect = true,
            clearCredentials = false,
        )
        _isConnected.value = false
        _isConnecting.value = false
        Log.i(TAG, "disconnectForUserStandby: 已断开，唤醒待机不重连")
    }

    /** 连接已断开时，禁止后续自动重连并进入唤醒待机（服务端空闲断线等） */
    fun suppressReconnectForWakeStandby() {
        userStandbyDisconnected = true
        mqttManager.disableReconnect()
        _isConnected.value = false
        _isConnecting.value = false
        Log.i(TAG, "suppressReconnectForWakeStandby: 唤醒待机，禁止自动重连")
    }

    fun isUserStandbyDisconnected(): Boolean = userStandbyDisconnected

    /** 用户再次发起连接（唤醒、拍照、录音、手动连接）时调用 */
    fun clearUserStandbyDisconnect() {
        if (!userStandbyDisconnected) return
        userStandbyDisconnected = false
        Log.d(TAG, "clearUserStandbyDisconnect: 允许重新连接")
    }

    /**
     * 待机黑屏休眠：断开并禁止自动重连，亮屏后仍保持唤醒待机直至用户主动连接。
     */
    fun disconnectForStandbySleep() {
        userStandbyDisconnected = true
        mqttManager.disableReconnect()
        mqttManager.disconnect(
            disableAutoReconnect = true,
            clearCredentials = false,
        )
        _isConnected.value = false
        _isConnecting.value = false
        Log.i(TAG, "disconnectForStandbySleep: 已断开，唤醒待机不重连")
    }

    /** 应用关闭：断开并禁止自动重连 */
    fun shutdown() {
        mqttManager.disableReconnect()
        mqttManager.disconnect(
            disableAutoReconnect = true,
            clearCredentials = true,
        )
        _isConnected.value = false
        _isConnecting.value = false
        _lastError.value = null
        Log.i(TAG, "shutdown: 小智会话已关闭")
    }

    private fun isNetworkConfigReady(): Boolean {
        val hasEndpoint = config.otaUrl.isNotBlank() || config.mqtt.isReady()
        return hasEndpoint && config.macAddress.isNotBlank()
    }

    private suspend fun performOtaAndConnect() {
        if (config.otaUrl.isNotBlank()) {
            Log.d(TAG, "OTA 检查...")
            val result = otaService.reportDeviceAndGetOta(
                clientId = config.uuid,
                deviceId = config.macAddress,
                otaUrl = config.otaUrl,
            )
            result.onSuccess { otaResponse ->
                // OTA 仍可能带 websocket 字段，仅可选同步 token；连接只用 mqtt
                otaResponse.websocket?.token?.takeIf { it.isNotBlank() }?.let { token ->
                    config = config.copy(token = token)
                }
                val mqtt = otaResponse.mqtt
                if (mqtt == null || mqtt.endpoint.isBlank()) {
                    Log.e(TAG, "OTA 未返回有效 mqtt，无法连接")
                    _lastError.value = "OTA 未返回 mqtt 配置"
                    return
                }
                val mqttConfig = MqttConfig.fromOta(
                    endpoint = mqtt.endpoint,
                    clientId = mqtt.clientId,
                    username = mqtt.username,
                    password = mqtt.password,
                    publishTopic = mqtt.publishTopic,
                    subscribeTopic = mqtt.subscribeTopic,
                    keepalive = mqtt.keepalive,
                )
                config = config.copy(mqtt = mqttConfig)
                configManager.saveConfig(config)
                Log.i(
                    TAG,
                    "OTA 下发 MQTT endpoint=${mqttConfig.endpoint} " +
                        "clientId=${mqttConfig.clientId} publish=${mqttConfig.publishTopic} " +
                        "subscribe=${mqttConfig.effectiveSubscribeTopic() ?: "(P2P/无需订阅)"}",
                )

                otaResponse.activation?.let { activation ->
                    Log.i(TAG, "需要设备激活: ${activation.code}")
                    _activationCode.value = activation.code
                    _awaitingActivation.value = true
                    _isConnecting.value = false
                    return
                }
                openMqtt()
            }.onFailure { e ->
                Log.e(TAG, "OTA 失败，尝试使用本地 MQTT 配置", e)
                _lastError.value = "OTA失败: ${e.message}"
                openMqtt()
            }
        } else {
            openMqtt()
        }
    }

    private suspend fun openMqtt() {
        val mqtt = config.mqtt
        if (!mqtt.isReady()) {
            Log.e(TAG, "MQTT 配置为空，请确认 OTA 返回 mqtt 字段")
            _lastError.value = "MQTT 地址未配置（OTA 未返回 mqtt）"
            return
        }
        Log.i(TAG, "connect MQTT: ${mqtt.endpoint} client=${mqtt.clientId.takeLast(12)}")
        mqttManager.connect(mqtt)
        val connected = withTimeoutOrNull(20_000) {
            while (!mqttManager.isConnected()) {
                delay(100)
            }
            true
        } ?: false
        if (connected) {
            _isConnected.value = true
            _isConnecting.value = false
            _lastError.value = null
            Log.i(TAG, "握手完成 session=${mqttManager.getSessionId()}")
        } else {
            Log.e(TAG, "握手超时 (20s)")
            _lastError.value = "MQTT/UDP 握手超时"
        }
    }

    companion object {
        private const val TAG = "Session"

        @Volatile
        private var instance: XiaozhiSessionManager? = null

        fun getInstance(application: Application): XiaozhiSessionManager {
            return instance ?: synchronized(this) {
                instance ?: XiaozhiSessionManager(application).also { instance = it }
            }
        }
    }
}
