package com.powerchina.zhixun.xiaozhi

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import androidx.core.content.ContextCompat
import com.powerchina.zhixun.dashcam.QuickPhotoCapture
import com.powerchina.zhixun.dashcam.SharedCameraCapture
import com.powerchina.zhixun.network.WebSocketManager
import com.powerchina.zhixun.physicalkey.PhotoKeyLog
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 作为 MCP 服务端响应小智云端（initialize / tools/list / tools/call）。
 */
object XiaozhiMcpHandler {

    private const val TAG = PhotoKeyLog.TAG
    private const val TOOL_TAKE_PHOTO = "self.camera.take_photo"
    private const val PHOTO_CAPTURE_TIMEOUT_MS = 15_000L
    /** TTS/STT 信号后留给 MCP tools/call 的窗口，避免与 fallback 抢锁 */
    private const val MCP_FALLBACK_DELAY_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val photoCaptureInFlight = AtomicBoolean(false)
    private var fallbackJob: Job? = null

    private data class PendingMcpReply(
        val id: Int,
        val question: String,
        val webSocket: WebSocketManager,
    )

    @Volatile
    private var pendingMcpReply: PendingMcpReply? = null

    fun isPhotoCaptureInFlight(): Boolean = photoCaptureInFlight.get()

    /** 拍照会话超时或 takePicture 挂死时由 UI 层调用 */
    fun abortStuckCapture(reason: String) {
        cancelTakePhotoFallback()
        pendingMcpReply = null
        photoCaptureInFlight.set(false)
        SharedCameraCapture.forceReset()
        Log.w(TAG, "中止卡住的拍照 reason=$reason")
    }

    /** STT/TTS 确认拍照意图后，延迟触发本地拍照（优先等待 MCP tools/call） */
    fun scheduleTakePhotoFallback(trigger: String) {
        if (!XiaozhiAppEvents.isPhotoSessionActive()) return
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            delay(MCP_FALLBACK_DELAY_MS)
            if (!XiaozhiAppEvents.isPhotoSessionActive()) return@launch
            if (photoCaptureInFlight.get()) {
                Log.d(TAG, "fallback 跳过：拍照已在进行 trigger=$trigger")
                return@launch
            }
            Log.i(TAG, "MCP 未在 ${MCP_FALLBACK_DELAY_MS}ms 内到达，触发 fallback trigger=$trigger")
            executeTakePhotoFromTtsFallback(trigger)
        }
    }

    fun cancelTakePhotoFallback() {
        fallbackJob?.cancel()
        fallbackJob = null
    }

    @Volatile
    private var application: Application? = null

    fun register(application: Application) {
        this.application = application
        val sessionManager = XiaozhiSessionManager.getInstance(application)
        scope.launch {
            sessionManager.webSocketManager.events.collect { event ->
                if (event is com.powerchina.zhixun.network.WebSocketEvent.MCPMessage) {
                    handleMcpMessage(sessionManager.webSocketManager, event.message)
                }
            }
        }
        Log.i(TAG, "已注册 MCP 处理器")
    }

    private fun handleMcpMessage(webSocket: WebSocketManager, message: String) {
        try {
            val root = JsonParser.parseString(message).asJsonObject
            val payload = root.getAsJsonObject("payload") ?: return
            val method = payload.get("method")?.asString ?: return
            if (method.startsWith("notifications/")) return

            val id = payload.get("id")?.asInt ?: return
            Log.i(TAG, "收到 MCP method=$method id=$id")
            when (method) {
                "initialize" -> handleInitialize(webSocket, id, payload)
                "tools/list" -> handleToolsList(webSocket, id)
                "tools/call" -> {
                    val name = payload.getAsJsonObject("params")?.get("name")?.asString
                    Log.i(TAG, "★ 服务端调用 tools/call name=$name")
                    handleToolsCall(webSocket, id, payload)
                }
                else -> webSocket.sendMcpError(id, "Method not implemented: $method")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 MCP 失败", e)
        }
    }

    private fun handleInitialize(webSocket: WebSocketManager, id: Int, payload: JsonObject) {
        val app = application ?: return
        val cfg = com.powerchina.zhixun.data.ConfigManager(app).loadConfig()
        val params = payload.getAsJsonObject("params")
        val capabilities = params?.getAsJsonObject("capabilities")
        val vision = capabilities?.getAsJsonObject("vision")
        val url = vision?.get("url")?.asString.orEmpty()
        val token = vision?.get("token")?.asString.orEmpty()
        if (url.isNotBlank()) {
            XiaozhiVisionRegistry.update(
                XiaozhiVisionConfig(
                    url = url,
                    token = token,
                    deviceId = cfg.macAddress,
                    clientId = cfg.uuid,
                ),
            )
            Log.i(TAG, "保存视觉端点 url=$url token=${token.take(8)}…")
        }
        webSocket.sendMcpInitializeResult(id)
        Log.d(TAG, "MCP initialize 已响应 device=${cfg.macAddress.takeLast(8)}")
    }

    private fun handleToolsList(webSocket: WebSocketManager, id: Int) {
        webSocket.sendMcpToolsListResult(id)
    }

    private fun handleToolsCall(webSocket: WebSocketManager, id: Int, payload: JsonObject) {
        val params = payload.getAsJsonObject("params") ?: run {
            webSocket.sendMcpError(id, "Missing params")
            return
        }
        val name = params.get("name")?.asString.orEmpty()
        if (name != TOOL_TAKE_PHOTO) {
            webSocket.sendMcpError(id, "Unknown tool: $name")
            return
        }
        val arguments = params.getAsJsonObject("arguments")
        val question = arguments?.get("question")?.asString?.ifBlank { null } ?: "请描述这张照片"

        val app = application ?: run {
            webSocket.sendMcpError(id, "Application not ready")
            return
        }

        scope.launch {
            runTakePhoto(
                app = app,
                webSocket = webSocket,
                mcpId = id,
                question = question,
                trigger = "mcp_tools_call",
            )
        }
    }

    /**
     * 部分服务端经 TTS 下发 take_photo（非 WebSocket MCP），收到后本地补拍。
     */
    fun executeTakePhotoFromTtsFallback(trigger: String) {
        val app = application ?: return
        if (!XiaozhiAppEvents.isPhotoSessionActive()) return
        val webSocket = XiaozhiSessionManager.getInstance(app).webSocketManager
        scope.launch {
            runTakePhoto(
                app = app,
                webSocket = webSocket,
                mcpId = null,
                question = "请描述这张照片",
                trigger = trigger,
            )
        }
    }

    fun containsTakePhotoToolSignal(text: String): Boolean =
        text.contains(TOOL_TAKE_PHOTO, ignoreCase = true)

    private suspend fun runTakePhoto(
        app: Application,
        webSocket: WebSocketManager,
        mcpId: Int?,
        question: String,
        trigger: String,
    ) {
        if (!photoCaptureInFlight.compareAndSet(false, true)) {
            if (mcpId != null) {
                pendingMcpReply = PendingMcpReply(mcpId, question, webSocket)
                cancelTakePhotoFallback()
                Log.i(TAG, "MCP tools/call 排队等待进行中的拍照 id=$mcpId trigger=$trigger")
                return
            }
            Log.w(TAG, "忽略并发 take_photo trigger=$trigger mcpId=null")
            return
        }
        cancelTakePhotoFallback()
        var sessionEngaged = false
        var recoverUi = false
        var recoverMessage: String? = null
        var sessionGeneration = 0L
        try {
            if (!XiaozhiAppEvents.isPhotoSessionActive()) {
                Log.w(TAG, "忽略无会话 take_photo trigger=$trigger")
                mcpId?.let { webSocket.sendMcpError(it, "无进行中的拍照请求") }
                return
            }
            sessionEngaged = true
            sessionGeneration = XiaozhiAppEvents.currentPhotoSessionGeneration()
            XiaozhiWakeForegroundService.pauseListening(app)
            ensureCameraPermission(app)
            withContext(Dispatchers.IO) {
                QuickPhotoCapture.preWarm(app)
            }
            val photoFile = withTimeout(PHOTO_CAPTURE_TIMEOUT_MS) {
                capturePhoto(app) ?: throw IllegalStateException("拍照失败，请检查相机权限")
            }
            XiaozhiAppEvents.emitPhotoResult(
                PhotoResult(
                    file = photoFile,
                    uploadResult = Result.success(Unit),
                    captureOnly = true,
                ),
            )
            val upload = XiaozhiPhotoUploader.uploadPhotoForMcp(
                application = app,
                photoFile = photoFile,
                prompt = question,
            )
            val visionResult = upload.getOrThrow()
            val toolPayload = XiaozhiVisionClient.buildToolCallResult(visionResult.response)
            if (mcpId != null) {
                webSocket.sendMcpToolResult(mcpId, toolPayload)
            } else {
                replyPendingMcp(toolPayload)
            }
            Log.i(
                TAG,
                "take_photo 完成 trigger=$trigger mcpId=$mcpId gen=$sessionGeneration",
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "take_photo 超时 trigger=$trigger mcpId=$mcpId", e)
            SharedCameraCapture.forceReset()
            sendMcpFailure(webSocket, mcpId, "拍照超时，请重试")
            recoverUi = true
            recoverMessage = "拍照超时，请重试"
        } catch (e: Exception) {
            Log.e(TAG, "take_photo 失败 trigger=$trigger mcpId=$mcpId", e)
            val userMessage = formatPhotoUploadError(e)
            sendMcpFailure(webSocket, mcpId, userMessage)
            recoverUi = true
            recoverMessage = userMessage
        } finally {
            pendingMcpReply = null
            photoCaptureInFlight.set(false)
            if (sessionEngaged) {
                XiaozhiAppEvents.endPhotoSession(
                    recoverUi = recoverUi,
                    recoverMessage = recoverMessage,
                    sessionGeneration = sessionGeneration,
                )
            }
        }
    }

    private fun ensureCameraPermission(app: Application) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("需要相机权限，请在对话页允许相机访问")
        }
    }

    private fun replyPendingMcp(toolPayload: JsonObject) {
        val pending = pendingMcpReply ?: return
        pendingMcpReply = null
        pending.webSocket.sendMcpToolResult(pending.id, toolPayload)
        Log.i(TAG, "MCP 排队回复已发送 id=${pending.id}")
    }

    private val photoHttpError = Regex("""HTTP [45]\d{2}""", RegexOption.IGNORE_CASE)

    private fun formatPhotoUploadError(error: Throwable): String {
        val raw = error.message?.trim().orEmpty()
        if (raw.isBlank()) return "拍照失败"
        if (raw.contains("服务器内部错误") || Regex("""HTTP 5\d{2}""").containsMatchIn(raw)) {
            return "服务器繁忙，请稍后重试"
        }
        if (photoHttpError.containsMatchIn(raw)) return "照片识别失败，请重试"
        return raw
    }

    private fun sendMcpFailure(webSocket: WebSocketManager, mcpId: Int?, message: String) {
        if (mcpId != null) {
            webSocket.sendMcpError(mcpId, message)
            return
        }
        val pending = pendingMcpReply ?: return
        pendingMcpReply = null
        pending.webSocket.sendMcpError(pending.id, message)
        Log.i(TAG, "MCP 排队错误已发送 id=${pending.id} msg=$message")
    }

    private suspend fun capturePhoto(application: Application): File? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                SharedCameraCapture.capture(application) { result ->
                    if (cont.isActive) {
                        cont.resume(result.getOrNull())
                    }
                }
                cont.invokeOnCancellation {
                    SharedCameraCapture.forceReset()
                }
            }
        }
}
