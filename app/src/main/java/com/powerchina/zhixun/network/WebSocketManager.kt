package com.powerchina.zhixun.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import okio.ByteString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * WebSocket事件类型
 */
sealed class WebSocketEvent {
    object Connected : WebSocketEvent()
    object Disconnected : WebSocketEvent()
    data class TextMessage(val message: String) : WebSocketEvent()
    data class BinaryMessage(val data: ByteArray) : WebSocketEvent()
    data class Error(val error: String) : WebSocketEvent()
    object HelloReceived : WebSocketEvent()
    data class MCPMessage(val message: String) : WebSocketEvent()
}

/**
 * WebSocket管理器
 */
class WebSocketManager(private val context: Context) {

    companion object {
        private const val TAG = "WebSocketManager"
        private const val RECONNECT_DELAY = 2000L // 增加重连延迟
        private const val HELLO_TIMEOUT = 15000L // 延长握手超时到15秒
        private const val CONNECT_TIMEOUT = 15L // 连接超时15秒
        private const val WRITE_TIMEOUT = 15L // 写入超时15秒
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isHandshakeComplete = false
    private var shouldReconnect = true
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 会话管理
    private var sessionId: String? = null
    private var helloTimeoutJob: Job? = null

    // 连接参数，用于自动重连
    private var lastUrl: String? = null
    private var lastDeviceId: String? = null
    private var lastToken: String? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var reconnectScheduled = false

    @Volatile
    private var connectInProgress = false

    @Volatile
    private var connectionGeneration = 0

    private val _events = MutableSharedFlow<WebSocketEvent>(
        replay = 1,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<WebSocketEvent> = _events

    private val textMessageListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** 注册文本消息监听（唤醒等模块使用，与 SharedFlow 并行分发） */
    fun addTextMessageListener(listener: (String) -> Unit): () -> Unit {
        textMessageListeners.add(listener)
        return { textMessageListeners.remove(listener) }
    }

    private val client = OkHttpClientFactory.create(
        context = context,
        connectTimeoutSec = CONNECT_TIMEOUT,
        readTimeoutSec = 0,
        writeTimeoutSec = WRITE_TIMEOUT
    )

    /**
     * 连接WebSocket
     */
    fun connect(url: String, deviceId: String, token: String) {
        if (isConnected() &&
            lastUrl == url &&
            lastDeviceId == deviceId &&
            lastToken == token
        ) {
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
        Log.d(TAG, "正在连接WebSocket: $url gen=$generation")

        // 关闭旧连接，避免并发 reader 触发 EOFException
        helloTimeoutJob?.cancel()
        webSocket?.cancel()
        webSocket = null
        isConnected = false
        isHandshakeComplete = false
        sessionId = null

        // 开启自动重连
        shouldReconnect = true

        // 保存连接参数用于自动重连
        lastUrl = url
        lastDeviceId = deviceId
        lastToken = token

        val request = Request.Builder()
            .url(url)
            .addHeader("Device-Id", deviceId)
            .addHeader("Client-Id", deviceId)
            .addHeader("Protocol-Version", "1")
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            private fun isStale(): Boolean = generation != connectionGeneration

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isStale()) return
                Log.d(TAG, "WebSocket连接成功，开始握手 gen=$generation")
                isConnected = true
                scope.launch {
                    sendHelloMessage()
                    startHelloTimeout()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isStale()) return
                val isMcp = try {
                    gson.fromJson(text, JsonObject::class.java).get("type")?.asString == "mcp"
                } catch (_: Exception) {
                    false
                }
                if (!isMcp) {
                    Log.d(TAG, "收到文本消息: $text")
                }
                textMessageListeners.forEach { listener ->
                    try {
                        listener(text)
                    } catch (e: Exception) {
                        Log.w(TAG, "文本消息监听器异常", e)
                    }
                }
                scope.launch {
                    handleTextMessage(text)
                    _events.emit(WebSocketEvent.TextMessage(text))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (isStale()) return
                Log.d(TAG, "收到二进制消息，长度: ${bytes.size}")
                scope.launch {
                    _events.emit(WebSocketEvent.BinaryMessage(bytes.toByteArray()))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (isStale()) return
                Log.d(TAG, "WebSocket正在关闭: $code - $reason gen=$generation")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isStale()) {
                    Log.d(TAG, "忽略旧连接 onClosed gen=$generation")
                    return
                }
                Log.d(TAG, "WebSocket已关闭: $code - $reason")
                reconnectAfterDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isStale()) {
                    Log.d(TAG, "忽略旧连接 onFailure gen=$generation: ${t.message}")
                    return
                }
                if (isBenignDisconnect(t)) {
                    Log.w(TAG, "WebSocket连接已断开（${t.javaClass.simpleName}），将自动重连")
                } else {
                    Log.e(TAG, "WebSocket连接失败", t)
                    scope.launch {
                        val detail = when (t) {
                            is javax.net.ssl.SSLHandshakeException ->
                                "SSL证书校验失败，请检查系统时间或网络环境"
                            else -> t.message ?: "连接失败"
                        }
                        _events.emit(WebSocketEvent.Error(detail))
                    }
                }
                reconnectAfterDisconnect()
            }
        })
    }

    /** 对端关闭、网络中断、ping 超时等常见断线，不应作为严重错误展示给用户 */
    private fun isBenignDisconnect(t: Throwable): Boolean {
        if (t is java.io.EOFException) return true
        if (t is java.net.SocketException) return true
        if (t is java.net.SocketTimeoutException) {
            val msg = t.message.orEmpty()
            if (msg.contains("ping", ignoreCase = true) && msg.contains("pong", ignoreCase = true)) {
                return true
            }
        }
        if (t is java.io.IOException && t.message?.contains("closed", ignoreCase = true) == true) {
            return true
        }
        return false
    }

    private fun reconnectAfterDisconnect() {
        val wasConnected = isConnected || isHandshakeComplete
        isConnected = false
        isHandshakeComplete = false
        sessionId = null
        helloTimeoutJob?.cancel()
        webSocket = null
        connectInProgress = false

        if (wasConnected) {
            scope.launch { _events.emit(WebSocketEvent.Disconnected) }
        }

        if (!shouldReconnect) {
            Log.d(TAG, "已禁用自动重连或手动关闭，跳过重连逻辑")
            reconnectScheduled = false
            return
        }

        if (reconnectScheduled) {
            Log.d(TAG, "已有重连任务排队，跳过")
            return
        }
        reconnectScheduled = true

        Log.d(TAG, "连接断开，${RECONNECT_DELAY}ms 后自动重连...")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            reconnectScheduled = false
            if (!shouldReconnect) return@launch
            val url = lastUrl
            val deviceId = lastDeviceId
            val token = lastToken
            if (url != null && deviceId != null && token != null) {
                connect(url, deviceId, token)
            }
        }
    }

    /**
     * 处理文本消息
     */
    private fun handleTextMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString

            when (type) {
                "hello" -> handleHelloResponse(json)
                "mcp" -> scope.launch { _events.emit(WebSocketEvent.MCPMessage(text)) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析文本消息失败", e)
        }
    }

    /**
     * 处理Hello响应
     */
    private fun handleHelloResponse(json: JsonObject) {
        val transport = json.get("transport")?.asString
        if (transport == "websocket") {
            // 提取session_id
            sessionId = json.get("session_id")?.asString
            isHandshakeComplete = true
            helloTimeoutJob?.cancel()

            Log.d(TAG, "握手完成1，session_id: $sessionId")
            connectInProgress = false
            scope.launch {
                Log.d(TAG, "握手完成2，session_id: $sessionId")
                Log.d(TAG, "发送HelloReceived事件")
                _events.emit(WebSocketEvent.HelloReceived)
                Log.d(TAG, "发送Connected事件")
                _events.emit(WebSocketEvent.Connected)
                Log.d(TAG, "事件发送完成")
            }
        } else {
            Log.e(TAG, "服务器返回的transport不匹配: $transport")
            connectInProgress = false
            scope.launch {
                _events.emit(WebSocketEvent.Error("握手失败：transport不匹配"))
            }
        }
    }

    /**
     * 启动Hello超时检查
     */
    private fun startHelloTimeout() {
        helloTimeoutJob = scope.launch {
            delay(HELLO_TIMEOUT)
            if (!isHandshakeComplete) {
                Log.e(TAG, "Hello握手超时")
                connectInProgress = false
                scope.launch {
                    _events.emit(WebSocketEvent.Error("握手超时"))
                }
                webSocket?.cancel()
                webSocket = null
                isConnected = false
                isHandshakeComplete = false
                sessionId = null
                reconnectAfterDisconnect()
            }
        }
    }

    /**
     * 发送Hello消息
     */
    private fun sendHelloMessage() {
        val hello = JsonObject().apply {
            addProperty("type", "hello")
            addProperty("version", 1)
            addProperty("transport", "websocket")
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

    /**
     * 发送开始监听消息
     */
    fun sendStartListening(mode: String = "auto") {
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "listen")
            addProperty("state", "start")
            addProperty("mode", mode)
        }
        sendTextMessage(gson.toJson(message))
    }

    /**
     * 发送停止监听消息
     */
    fun sendStopListening() {
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "listen")
            addProperty("state", "stop")
        }
        sendTextMessage(gson.toJson(message))
    }

    /**
     * 发送唤醒词检测消息
     */
    fun sendWakeWordDetected(text: String) {
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "listen")
            addProperty("state", "detect")
            addProperty("text", text)
            addProperty("source", "text")
        }
        sendTextMessage(gson.toJson(message))
    }

    /**
     * 发送中断消息
     */
    fun sendAbort(reason: String = "user_interrupt") {
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "abort")
            addProperty("reason", reason)
        }
        sendTextMessage(gson.toJson(message))
    }

    /**
     * 发送文本消息
     */
    fun sendTextMessage(message: String) {
        if (isConnected && webSocket != null) {
            try {
                val success = webSocket!!.send(message)
                if (success) {
                    val logBody = if (message.length > 500) {
                        "${message.take(200)}...(len=${message.length})"
                    } else {
                        message
                    }
                    Log.d(TAG, "发送文本消息: $logBody")
                } else {
                    Log.w(TAG, "发送文本消息失败，WebSocket可能已关闭")
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送文本消息异常", e)
            }
        } else {
            Log.w(TAG, "WebSocket未连接，无法发送消息")
        }
    }

    /**
     * 发送二进制消息
     */
    fun sendBinaryMessage(data: ByteArray) {
        if (isConnected && isHandshakeComplete && webSocket != null) {
            try {
                val success = webSocket!!.send(ByteString.of(*data))
                if (success) {
//                    Log.d(TAG, "发送二进制消息，长度: ${data.size}")
                } else {
                    Log.w(TAG, "发送二进制消息失败，WebSocket可能已关闭")
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送二进制消息异常", e)
            }
        } else {
            Log.w(TAG, "WebSocket未就绪，无法发送二进制消息")
        }
    }

    /**
     * 发送图片消息（JPEG base64，兼容 type=img 协议草案）
     */
    fun sendImage(jpegBytes: ByteArray, mimeType: String = "image/jpeg") {
        if (!isConnected() || !isHandshakeComplete || webSocket == null) {
            Log.w(TAG, "WebSocket未就绪，无法发送图片")
            return
        }
        val rawBase64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "img")
            addProperty("mime", mimeType)
            addProperty("base64", rawBase64)
        }
        val json = gson.toJson(message)
        Log.i(TAG, "发送图片消息 jpeg=${jpegBytes.size}B base64=${rawBase64.length} session=$sessionId")
        sendRawTextMessage(json)
    }

    /** 携带图片的 detect 提问（部分服务端在此字段取图） */
    fun sendVisionDetectWithImage(text: String, jpegBytes: ByteArray) {
        if (!isConnected() || !isHandshakeComplete || webSocket == null) {
            Log.w(TAG, "WebSocket未就绪，无法发送视觉提问")
            return
        }
        val rawBase64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
        val dataUri = "data:image/jpeg;base64,$rawBase64"
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "listen")
            addProperty("state", "detect")
            addProperty("text", text)
            addProperty("source", "camera")
            addProperty("mime", "image/jpeg")
            addProperty("image", dataUri)
            addProperty("base64", rawBase64)
        }
        sendRawTextMessage(gson.toJson(message))
        Log.i(TAG, "发送视觉提问(含图片): $text jpeg=${jpegBytes.size}B")
    }

    /** 图片识别后的文本提问 */
    fun sendVisionQuery(text: String) {
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

    /**
     * 发送文本请求（兼容旧接口）
     */
    fun sendTextRequest(text: String) {
        sendVisionQuery(text)
    }

    private fun sendRawTextMessage(message: String) {
        if (!isConnected() || webSocket == null) {
            Log.w(TAG, "WebSocket未就绪，无法发送消息")
            return
        }
        try {
            val success = webSocket!!.send(message)
            if (!success) {
                Log.w(TAG, "发送消息失败，WebSocket可能已关闭")
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送消息异常", e)
        }
    }

    fun sendMcpPayload(payload: JsonObject) {
        if (!isConnected() || !isHandshakeComplete || webSocket == null) {
            Log.w(TAG, "WebSocket未就绪，无法发送 MCP")
            return
        }
        val message = JsonObject().apply {
            sessionId?.let { addProperty("session_id", it) }
            addProperty("type", "mcp")
            add("payload", payload)
        }
        sendRawTextMessage(gson.toJson(message))
    }

    fun sendMcpInitializeResult(id: Int) {
        val result = JsonObject().apply {
            addProperty("protocolVersion", "2024-11-05")
            add(
                "capabilities",
                JsonObject().apply {
                    add("tools", JsonObject())
                },
            )
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
                "Take a photo with the device camera and explain it.",
            )
            add(
                "inputSchema",
                JsonObject().apply {
                    addProperty("type", "object")
                    add(
                        "properties",
                        JsonObject().apply {
                            add(
                                "question",
                                JsonObject().apply {
                                    addProperty("type", "string")
                                },
                            )
                        },
                    )
                    add("required", com.google.gson.JsonArray().apply { add("question") })
                },
            )
        }
        val tools = com.google.gson.JsonArray()
        tools.add(tool)
        sendMcpPayload(
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                add(
                    "result",
                    JsonObject().apply {
                        add("tools", tools)
                    },
                )
            },
        )
    }

    fun sendMcpToolResult(id: Int, result: JsonObject) {
        sendMcpPayload(
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                add("result", result)
            },
        )
    }

    fun sendMcpError(id: Int, message: String) {
        sendMcpPayload(
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                add(
                    "error",
                    JsonObject().apply {
                        addProperty("message", message)
                    },
                )
            },
        )
    }

    /**
     * 断开连接
     *
     * @param disableAutoReconnect 是否禁止后续自动重连（应用退出、用户主动断开）
     * @param clearCredentials 是否清除连接参数（完全关闭时使用）
     */
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
        val wasActive = isConnected || isHandshakeComplete
        webSocket?.close(1000, "正常关闭")
        webSocket = null
        isConnected = false
        isHandshakeComplete = false
        sessionId = null
        if (clearCredentials) {
            lastUrl = null
            lastDeviceId = null
            lastToken = null
        }
        if (wasActive) {
            scope.launch { _events.emit(WebSocketEvent.Disconnected) }
        }
    }

    /** 是否允许在断线后自动重连 */
    fun isAutoReconnectEnabled(): Boolean = shouldReconnect

    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean = isConnected && isHandshakeComplete

    /**
     * 获取会话ID
     */
    fun getSessionId(): String? = sessionId

    /**
     * 重新启用自动重连
     */
    fun enableReconnect() {
        shouldReconnect = true
    }

    /**
     * 禁用自动重连
     */
    fun disableReconnect() {
        shouldReconnect = false
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}