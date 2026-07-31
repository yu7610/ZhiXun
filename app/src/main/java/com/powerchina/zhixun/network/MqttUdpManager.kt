package com.powerchina.zhixun.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.powerchina.zhixun.data.MqttConfig
import com.powerchina.zhixun.physicalkey.PhotoKeyLog
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * MQTT + UDP 混合协议管理器。
 *
 * - MQTT：控制消息（hello / listen / abort / mcp / goodbye）
 * - UDP：AES-CTR 加密 Opus 音频
 *
 * 协议文档：https://github.com/78/xiaozhi-esp32/blob/main/docs/mqtt-udp_zh.md
 */
class MqttUdpManager(@Suppress("UNUSED_PARAMETER") private val context: Context) {

    companion object {
        /** 与拍照/MCP 全链路统一，过滤：adb logcat -s ZhiXunPhotoKey */
        private const val TAG = PhotoKeyLog.TAG
        private const val RECONNECT_DELAY = 2000L
        private const val HELLO_TIMEOUT = 15000L
        private const val AUDIO_HEADER_SIZE = 16
        private const val LOG_PAYLOAD_MAX = 512
        private const val DEFAULT_MQTT_PORT = 8883

        private fun summarizePayload(text: String): String =
            if (text.length <= LOG_PAYLOAD_MAX) text else text.take(LOG_PAYLOAD_MAX) + "…(${text.length}B)"
    }

    /** MQTT 已连但 hello 未完成时，MCP 先入队，握手后补发 */
    private val pendingMcpOutbound = ConcurrentLinkedQueue<String>()

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mqttClient: MqttClient? = null
    private var udpSocket: DatagramSocket? = null
    private var udpReceiverThread: Thread? = null

    @Volatile private var mqttConnected = false
    @Volatile private var isHandshakeComplete = false
    @Volatile private var shouldReconnect = true
    @Volatile private var reconnectScheduled = false
    @Volatile private var connectInProgress = false
    @Volatile private var connectionGeneration = 0

    private var sessionId: String? = null
    private var helloTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastConfig: MqttConfig? = null

    // UDP / AES
    private var udpServer: String? = null
    private var udpPort: Int = 0
    private var udpAddress: InetAddress? = null
    private var aesKey: ByteArray? = null
    private var aesNonceTemplate: ByteArray? = null
    private val localSequence = AtomicLong(0)
    private val remoteSequence = AtomicLong(0)
    private val udpReceiveRunning = AtomicBoolean(false)
    private val channelLock = Any()

    private val _events = MutableSharedFlow<MqttUdpEvent>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<MqttUdpEvent> = _events

    @Volatile private var loggedFirstUdpRx = false
    @Volatile private var loggedFirstUdpTx = false

    /** goodbye 后静默重建 UDP，避免 Disconnected/Connected 触发上层重复拍照/开麦 */
    @Volatile private var silentAudioRefresh = false
    @Volatile private var lastHelloSentAtMs = 0L

    private val textMessageListeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addTextMessageListener(listener: (String) -> Unit): () -> Unit {
        textMessageListeners.add(listener)
        return { textMessageListeners.remove(listener) }
    }

    fun connect(config: MqttConfig) {
        if (!config.isReady()) {
            Log.e(TAG, "MQTT 配置不完整，无法连接")
            scope.launch { _events.emit(MqttUdpEvent.Error("MQTT 配置不完整")) }
            return
        }
        if (isConnected() && lastConfig == config) {
            Log.d(TAG, "已就绪，跳过 connect session=$sessionId")
            return
        }
        if (connectInProgress) {
            Log.d(TAG, "连接进行中，跳过 connect")
            return
        }
        connectInProgress = true
        reconnectJob?.cancel()
        reconnectScheduled = false
        val generation = ++connectionGeneration
        shouldReconnect = true
        lastConfig = config

        closeAudioChannel(sendGoodbye = false)
        disconnectMqttOnly()
        isHandshakeComplete = false
        sessionId = null

        scope.launch {
            try {
                connectMqtt(config, generation)
            } catch (e: Exception) {
                Log.e(TAG, "MQTT 连接异常", e)
                connectInProgress = false
                _events.emit(MqttUdpEvent.Error(e.message ?: "MQTT 连接失败"))
                scheduleReconnect()
            }
        }
    }

    private fun connectMqtt(config: MqttConfig, generation: Int) {
        val (host, port) = parseEndpoint(config.endpoint)
        val useSsl = port == 8883
        val serverUri = if (useSsl) "ssl://$host:$port" else "tcp://$host:$port"
        Log.i(TAG, "连接 MQTT: $serverUri clientId=${config.clientId} gen=$generation")

        val client = MqttClient(serverUri, config.clientId, MemoryPersistence())
        mqttClient = client

        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = false
            keepAliveInterval = config.keepalive.coerceAtLeast(30)
            connectionTimeout = 15
            userName = config.username
            password = config.password.toCharArray()
            if (useSsl) {
                socketFactory = javax.net.ssl.SSLSocketFactory.getDefault()
            }
        }

        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (generation != connectionGeneration) return
                Log.i(TAG, "MQTT 已连接 reconnect=$reconnect uri=$serverURI")
                mqttConnected = true
                flushPendingMcpOutbound()
                try {
                    val topic = config.effectiveSubscribeTopic()
                    if (topic != null) {
                        client.subscribe(topic, 0)
                        Log.i(TAG, "已订阅: $topic")
                    } else {
                        Log.i(TAG, "subscribe_topic 为空/null，使用网关 P2P，跳过订阅")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "订阅失败", e)
                }
                scope.launch { openAudioChannel(generation) }
            }

            override fun connectionLost(cause: Throwable?) {
                if (generation != connectionGeneration) return
                Log.w(TAG, "MQTT 断开: ${cause?.message}")
                reconnectAfterDisconnect()
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (generation != connectionGeneration) return
                val text = message?.payload?.toString(Charsets.UTF_8) ?: return
                scope.launch { onMqttText(text) }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        client.connect(options)
        // connectComplete 回调里继续 openAudioChannel
    }

    private suspend fun openAudioChannel(generation: Int) {
        if (generation != connectionGeneration) return
        val now = System.currentTimeMillis()
        if (now - lastHelloSentAtMs < 800) {
            Log.w(TAG, "hello 发送过快，跳过（防 goodbye 抖动）")
            return
        }
        lastHelloSentAtMs = now
        isHandshakeComplete = false
        sessionId = null
        helloTimeoutJob?.cancel()
        startHelloTimeout()
        sendHelloMessage()
    }

    private fun onMqttText(text: String) {
        val msgType = try {
            gson.fromJson(text, JsonObject::class.java).get("type")?.asString
        } catch (_: Exception) {
            null
        }
        if (msgType != "mcp") {
            Log.d(TAG, "收到 MQTT 消息: ${summarizePayload(text)}")
        }
        Log.i(PhotoKeyLog.TAG, "服务端← ${msgType ?: "unknown"}: ${summarizePayload(text)}")

        textMessageListeners.forEach { listener ->
            try {
                listener(text)
            } catch (e: Exception) {
                Log.w(TAG, "文本消息监听器异常", e)
            }
        }

        handleTextMessage(text)
        scope.launch { _events.emit(MqttUdpEvent.TextMessage(text)) }
    }

    private fun handleTextMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            when (json.get("type")?.asString) {
                "hello" -> handleHelloResponse(json)
                "goodbye" -> {
                    val sid = json.get("session_id")?.asString
                    if (sid == null || sid == sessionId) {
                        Log.i(TAG, "收到 goodbye，关闭音频通道（延迟静默重建，避免打断 TTS）")
                        closeAudioChannel(sendGoodbye = false)
                        isHandshakeComplete = false
                        connectInProgress = false
                        // 勿发 Disconnected/Connected；延迟重建，给当前 TTS 播完的时间，减少重复播报
                        if (shouldReconnect && mqttConnected) {
                            silentAudioRefresh = true
                            scope.launch {
                                delay(2_000)
                                if (!mqttConnected || !shouldReconnect) return@launch
                                if (isHandshakeComplete) return@launch
                                openAudioChannel(connectionGeneration)
                            }
                        }
                    }
                }
                "mcp" -> scope.launch { _events.emit(MqttUdpEvent.MCPMessage(text)) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析文本消息失败", e)
        }
    }

    private fun handleHelloResponse(json: JsonObject) {
        val transport = json.get("transport")?.asString
        if (transport != "udp") {
            Log.e(TAG, "服务器返回的 transport 不匹配: $transport")
            connectInProgress = false
            scope.launch { _events.emit(MqttUdpEvent.Error("握手失败：transport 不匹配")) }
            return
        }

        sessionId = json.get("session_id")?.asString
        val udpObj = json.getAsJsonObject("udp")
        if (udpObj == null) {
            Log.e(TAG, "Hello 响应缺少 udp 字段")
            connectInProgress = false
            scope.launch { _events.emit(MqttUdpEvent.Error("握手失败：缺少 UDP 信息")) }
            return
        }

        val server = udpObj.get("server")?.asString
        val port = udpObj.get("port")?.asInt ?: 0
        val keyHex = udpObj.get("key")?.asString
        val nonceHex = udpObj.get("nonce")?.asString
        if (server.isNullOrBlank() || port <= 0 || keyHex.isNullOrBlank() || nonceHex.isNullOrBlank()) {
            Log.e(TAG, "UDP 参数无效")
            connectInProgress = false
            scope.launch { _events.emit(MqttUdpEvent.Error("握手失败：UDP 参数无效")) }
            return
        }

        val key = decodeHex(keyHex)
        val nonce = decodeHex(nonceHex)
        if (key == null || nonce == null || key.size != 16 || nonce.size != 16) {
            Log.e(TAG, "AES key/nonce 长度无效")
            connectInProgress = false
            scope.launch { _events.emit(MqttUdpEvent.Error("握手失败：加密参数无效")) }
            return
        }

        synchronized(channelLock) {
            udpServer = server
            udpPort = port
            aesKey = key
            aesNonceTemplate = nonce
            localSequence.set(0)
            remoteSequence.set(0)
            loggedFirstUdpRx = false
            loggedFirstUdpTx = false
        }

        try {
            startUdp(server, port)
            // 移动网络必须先发一包打洞，否则收不到服务端下行 TTS
            punchUdpHole()
        } catch (e: Exception) {
            Log.e(TAG, "UDP 连接失败", e)
            connectInProgress = false
            scope.launch { _events.emit(MqttUdpEvent.Error("UDP 连接失败: ${e.message}")) }
            return
        }

        isHandshakeComplete = true
        helloTimeoutJob?.cancel()
        connectInProgress = false
        flushPendingMcpOutbound()
        Log.i(
            TAG,
            "握手完成 session=$sessionId udp=$server:$port silentRefresh=$silentAudioRefresh",
        )
        val quiet = silentAudioRefresh
        silentAudioRefresh = false
        if (quiet) {
            // 仅刷新 UDP/session，上层仍视为已连接
            Log.i(TAG, "静默音频通道刷新完成，跳过 Connected 事件")
            return
        }
        scope.launch {
            _events.emit(MqttUdpEvent.HelloReceived)
            _events.emit(MqttUdpEvent.Connected)
        }
    }

    private fun startUdp(server: String, port: Int) {
        stopUdpReceiver()
        val address = InetAddress.getByName(server)
        synchronized(channelLock) {
            udpSocket?.close()
            // 显式 bind，保证收发使用同一本地端口（NAT 打洞）
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(0))
            socket.soTimeout = 0
            try {
                socket.connect(address, port)
            } catch (e: Exception) {
                Log.w(TAG, "UDP connect 失败，回退 sendto: ${e.message}")
            }
            udpSocket = socket
            udpAddress = address
            udpPort = port
        }
        udpReceiveRunning.set(true)
        udpReceiverThread = thread(name = "mqtt-udp-rx", isDaemon = true) {
            val buf = ByteArray(4096)
            while (udpReceiveRunning.get()) {
                val socket = udpSocket ?: break
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (packet.length > 0) {
                        val data = packet.data.copyOf(packet.length)
                        handleUdpPacket(data)
                    }
                } catch (e: Exception) {
                    if (udpReceiveRunning.get()) {
                        Log.w(TAG, "UDP 接收异常: ${e.message}")
                    }
                    break
                }
            }
        }
        Log.i(TAG, "UDP 已就绪 $server:$port localPort=${udpSocket?.localPort}")
    }

    /** 发送空 Opus 帧，打通 NAT 回程，使服务端 TTS 能到达本机 */
    private fun punchUdpHole() {
        val ok = sendUdpPayload(ByteArray(0), isPunch = true)
        Log.i(TAG, "UDP NAT 打洞 ${if (ok) "已发送" else "失败"}")
    }

    private fun handleUdpPacket(data: ByteArray) {
        if (data.size < AUDIO_HEADER_SIZE) {
            Log.e(TAG, "无效音频包长度: ${data.size}")
            return
        }
        // 兼容部分网关：type 异常时仍尝试解密（与 py-xiaozhi 一致）
        if (data[0] != 0x01.toByte()) {
            Log.w(TAG, "音频包 type=${data[0].toInt() and 0xFF}，仍尝试解密")
        }
        val payloadLen = ByteBuffer.wrap(data, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        val sequence = ByteBuffer.wrap(data, 12, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val available = data.size - AUDIO_HEADER_SIZE
        if (available <= 0) {
            return
        }
        // 以实际包长为准；header 长度仅作参考（避免过严丢弃导致无声）
        val cipherLen = if (payloadLen in 1..available) payloadLen else available
        if (payloadLen != available) {
            Log.w(TAG, "音频长度不一致 header=$payloadLen actual=$available，使用 $cipherLen")
        }

        val lastRemote = remoteSequence.get()
        if (sequence != 0L && sequence <= lastRemote) {
            Log.w(TAG, "丢弃旧/重复序列号: $sequence last=$lastRemote")
            return
        }
        if (lastRemote != 0L && sequence != lastRemote + 1) {
            Log.w(TAG, "序列号跳跃: $sequence expected=${lastRemote + 1}")
        }

        val key = aesKey ?: return
        val nonce = data.copyOfRange(0, AUDIO_HEADER_SIZE)
        val encrypted = data.copyOfRange(AUDIO_HEADER_SIZE, AUDIO_HEADER_SIZE + cipherLen)
        val plain = try {
            aesCtr(key, nonce, encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "音频解密失败 len=$cipherLen", e)
            return
        }
        if (sequence != 0L) {
            remoteSequence.set(sequence)
        }
        if (!loggedFirstUdpRx) {
            loggedFirstUdpRx = true
            Log.i(TAG, "首包 UDP 下行解密成功 ${plain.size}B seq=$sequence")
        }
        scope.launch { _events.emit(MqttUdpEvent.BinaryMessage(plain)) }
    }

    private fun startHelloTimeout() {
        helloTimeoutJob = scope.launch {
            delay(HELLO_TIMEOUT)
            if (!isHandshakeComplete) {
                Log.e(TAG, "Hello 握手超时")
                connectInProgress = false
                _events.emit(MqttUdpEvent.Error("握手超时"))
                closeAudioChannel(sendGoodbye = false)
                scheduleReconnect()
            }
        }
    }

    private fun sendHelloMessage() {
        val hello = JsonObject().apply {
            addProperty("type", "hello")
            addProperty("version", 3)
            addProperty("transport", "udp")
            add("features", JsonObject().apply {
                addProperty("mcp", true)
            })
            add("audio_params", JsonObject().apply {
                addProperty("format", "opus")
                addProperty("sample_rate", 16000)
                addProperty("channels", 1)
                addProperty("frame_duration", 60)
            })
        }
        sendTextMessage(gson.toJson(hello))
    }

    fun sendStartListening(mode: String = "auto") {
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "listen")
            addProperty("state", "start")
            addProperty("mode", mode)
        }
        sendTextMessage(gson.toJson(message))
    }

    fun sendStopListening() {
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "listen")
            addProperty("state", "stop")
        }
        sendTextMessage(gson.toJson(message))
    }

    fun sendWakeWordDetected(text: String) {
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "listen")
            addProperty("state", "detect")
            addProperty("text", text)
        }
        sendTextMessage(gson.toJson(message))
    }

    fun sendAbort(reason: String = "user_interrupt") {
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "abort")
            addProperty("reason", reason)
        }
        sendTextMessage(gson.toJson(message))
    }

    fun sendTextMessage(message: String): Boolean {
        val topic = lastConfig?.publishTopic
        val client = mqttClient
        if (!mqttConnected || client == null || topic.isNullOrBlank()) {
            Log.w(TAG, "MQTT 未就绪，无法发送消息")
            return false
        }
        return try {
            client.publish(topic, MqttMessage(message.toByteArray(Charsets.UTF_8)).apply { qos = 0 })
            Log.i(TAG, "MQTT→ ${summarizePayload(message)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送 MQTT 消息异常", e)
            false
        }
    }

    fun sendBinaryMessage(data: ByteArray) {
        if (!isHandshakeComplete) {
            Log.w(TAG, "音频通道未就绪，无法发送")
            return
        }
        sendUdpPayload(data, isPunch = false)
    }

    /**
     * 发送加密 UDP 音频。nonce 构造对齐 py-xiaozhi / esp32：
     * type|flags|payload_len|ssrc(原样)|timestamp(原样)|sequence
     */
    private fun sendUdpPayload(data: ByteArray, isPunch: Boolean): Boolean {
        val key: ByteArray
        val nonceTemplate: ByteArray
        val address: InetAddress
        val port: Int
        val socket: DatagramSocket
        synchronized(channelLock) {
            key = aesKey ?: return false
            nonceTemplate = aesNonceTemplate ?: return false
            address = udpAddress ?: return false
            port = udpPort
            socket = udpSocket ?: return false
        }
        if (nonceTemplate.size != AUDIO_HEADER_SIZE || data.size > 0xFFFF) {
            Log.e(TAG, "无效 nonce 或负载长度: ${data.size}")
            return false
        }

        val seq = localSequence.incrementAndGet()
        // 对齐 py-xiaozhi：保留服务端 nonce 中的 ssrc+timestamp，只改 length/sequence
        val nonce = nonceTemplate.copyOf()
        ByteBuffer.wrap(nonce, 2, 2).order(ByteOrder.BIG_ENDIAN)
            .putShort((data.size and 0xFFFF).toShort())
        ByteBuffer.wrap(nonce, 12, 4).order(ByteOrder.BIG_ENDIAN).putInt(seq.toInt())

        val encrypted = try {
            aesCtr(key, nonce, data)
        } catch (e: Exception) {
            Log.e(TAG, "音频加密失败", e)
            return false
        }
        val packetBytes = ByteArray(AUDIO_HEADER_SIZE + encrypted.size)
        System.arraycopy(nonce, 0, packetBytes, 0, AUDIO_HEADER_SIZE)
        System.arraycopy(encrypted, 0, packetBytes, AUDIO_HEADER_SIZE, encrypted.size)
        return try {
            if (socket.isConnected) {
                socket.send(DatagramPacket(packetBytes, packetBytes.size))
            } else {
                socket.send(DatagramPacket(packetBytes, packetBytes.size, address, port))
            }
            if (!loggedFirstUdpTx) {
                loggedFirstUdpTx = true
                Log.i(TAG, "首包 UDP 上行 ${data.size}B punch=$isPunch seq=$seq -> $address:$port")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP 发送失败", e)
            false
        }
    }

    fun sendTextRequest(text: String) {
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "listen")
            addProperty("state", "detect")
            addProperty("text", text)
            addProperty("source", "text")
        }
        sendRawTextMessage(gson.toJson(message))
        Log.i(TAG, "发送视觉提问: $text")
    }

    private fun sendRawTextMessage(message: String) {
        if (!mqttConnected || mqttClient == null) {
            Log.w(TAG, "MQTT 未就绪，无法发送消息")
            return
        }
        sendTextMessage(message)
    }

    /**
     * MCP 走 MQTT 控制通道，只需 MQTT 已连接，不必等 UDP hello。
     * （服务端常在 hello 完成前下发 initialize）
     */
    fun sendMcpPayload(payload: JsonObject) {
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "mcp")
            add("payload", payload)
        }
        val json = gson.toJson(message)
        val method = payload.get("method")?.asString
            ?: payload.get("result")?.let { "result" }
            ?: payload.get("error")?.let { "error" }
            ?: "mcp"
        val id = payload.get("id")?.asInt
        if (!mqttConnected || mqttClient == null) {
            pendingMcpOutbound.offer(json)
            Log.w(TAG, "MCP 排队 method=$method id=$id（MQTT 未连接） queue=${pendingMcpOutbound.size}")
            return
        }
        if (sendTextMessage(json)) {
            Log.i(TAG, "MCP 已发送 method=$method id=$id session=$sessionId")
        } else {
            pendingMcpOutbound.offer(json)
            Log.w(TAG, "MCP 发送失败已排队 method=$method id=$id")
        }
    }

    private fun flushPendingMcpOutbound() {
        if (!mqttConnected) return
        var n = 0
        while (true) {
            val msg = pendingMcpOutbound.poll() ?: break
            if (sendTextMessage(msg)) {
                n++
            } else {
                pendingMcpOutbound.offer(msg)
                break
            }
        }
        if (n > 0) {
            Log.i(TAG, "已补发排队 MCP ${n} 条")
        }
    }

    fun sendMcpInitializeResult(id: Int) {
        val result = JsonObject().apply {
            addProperty("protocolVersion", "2024-11-05")
            add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
            add(
                "serverInfo",
                JsonObject().apply {
                    addProperty("name", "zhixun-android")
                    addProperty("version", "1.0.0")
                },
            )
        }
        sendMcpPayload(
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                add("result", result)
            },
        )
    }

    fun sendMcpToolsListResult(id: Int) {
        val tool = JsonObject().apply {
            addProperty("name", "self.camera.take_photo")
            addProperty(
                "description",
                "Always remember you have a camera.\n" +
                    "Take a photo, detect safety hazards in the image.\n" +
                    "If hazards are found, describe the hazards; " +
                    "otherwise, respond that no safety hazards are detected.\n" +
                    "Return:\n" +
                    "  A JSON object that provides the photo information.",
            )
            add(
                "inputSchema",
                JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject())
                    add("required", com.google.gson.JsonArray())
                },
            )
        }
        val tools = com.google.gson.JsonArray()
        tools.add(tool)
        sendMcpPayload(
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                add("result", JsonObject().apply { add("tools", tools) })
            },
        )
    }

    fun sendMcpToolResult(id: Int, result: JsonObject) {
        Log.i(PhotoKeyLog.TAG, "回传服务端 MCP result id=$id: $result")
        sendMcpPayload(
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                add("result", result)
            },
        )
    }

    fun sendMcpError(id: Int, message: String) {
        Log.w(PhotoKeyLog.TAG, "回传服务端 MCP error id=$id: $message")
        sendMcpPayload(
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                add(
                    "error",
                    JsonObject().apply { addProperty("message", message) },
                )
            },
        )
    }

    fun disconnect(
        disableAutoReconnect: Boolean = true,
        clearCredentials: Boolean = disableAutoReconnect,
    ) {
        if (disableAutoReconnect) {
            shouldReconnect = false
            reconnectJob?.cancel()
            reconnectScheduled = false
        }
        connectInProgress = false
        connectionGeneration++
        helloTimeoutJob?.cancel()
        val wasActive = mqttConnected || isHandshakeComplete
        closeAudioChannel(sendGoodbye = wasActive && isHandshakeComplete)
        disconnectMqttOnly()
        isHandshakeComplete = false
        sessionId = null
        if (clearCredentials) {
            lastConfig = null
        }
        if (wasActive) {
            scope.launch { _events.emit(MqttUdpEvent.Disconnected) }
        }
    }

    fun isAutoReconnectEnabled(): Boolean = shouldReconnect

    fun isConnected(): Boolean = mqttConnected && isHandshakeComplete

    fun getSessionId(): String? = sessionId

    fun enableReconnect() {
        shouldReconnect = true
    }

    fun disableReconnect() {
        shouldReconnect = false
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }

    private fun reconnectAfterDisconnect() {
        val wasConnected = mqttConnected || isHandshakeComplete
        mqttConnected = false
        isHandshakeComplete = false
        sessionId = null
        helloTimeoutJob?.cancel()
        closeAudioChannel(sendGoodbye = false)
        connectInProgress = false
        mqttClient = null

        if (wasConnected) {
            scope.launch { _events.emit(MqttUdpEvent.Disconnected) }
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) {
            reconnectScheduled = false
            return
        }
        if (reconnectScheduled) return
        reconnectScheduled = true
        Log.d(TAG, "连接断开，${RECONNECT_DELAY}ms 后自动重连...")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            reconnectScheduled = false
            if (!shouldReconnect) return@launch
            val cfg = lastConfig
            if (cfg != null && cfg.isReady()) {
                connect(cfg)
            }
        }
    }

    private fun closeAudioChannel(sendGoodbye: Boolean) {
        if (sendGoodbye && sessionId != null && mqttConnected) {
            val message = JsonObject().apply {
                addProperty("session_id", sessionId)
                addProperty("type", "goodbye")
            }
            try {
                sendTextMessage(gson.toJson(message))
            } catch (_: Exception) {
            }
        }
        stopUdpReceiver()
        synchronized(channelLock) {
            udpSocket?.close()
            udpSocket = null
            udpAddress = null
            aesKey = null
            aesNonceTemplate = null
            udpServer = null
            udpPort = 0
            localSequence.set(0)
            remoteSequence.set(0)
        }
    }

    private fun stopUdpReceiver() {
        udpReceiveRunning.set(false)
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        try {
            udpReceiverThread?.join(500)
        } catch (_: Exception) {
        }
        udpReceiverThread = null
    }

    private fun disconnectMqttOnly() {
        pendingMcpOutbound.clear()
        try {
            val client = mqttClient
            mqttClient = null
            mqttConnected = false
            if (client != null) {
                if (client.isConnected) {
                    client.disconnectForcibly(1000, 1000)
                }
                client.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "断开 MQTT 异常: ${e.message}")
        }
    }

    private fun parseEndpoint(endpoint: String): Pair<String, Int> {
        val trimmed = endpoint.trim()
        val idx = trimmed.lastIndexOf(':')
        return if (idx > 0) {
            val host = trimmed.substring(0, idx)
            val port = trimmed.substring(idx + 1).toIntOrNull() ?: DEFAULT_MQTT_PORT
            host to port
        } else {
            trimmed to DEFAULT_MQTT_PORT
        }
    }

    private fun decodeHex(hex: String): ByteArray? {
        val s = hex.trim()
        if (s.length % 2 != 0) return null
        return try {
            ByteArray(s.length / 2) { i ->
                s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun aesCtr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(input)
    }
}
