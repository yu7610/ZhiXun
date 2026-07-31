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
import com.powerchina.zhixun.network.MqttUdpManager
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
    private const val MCP_FALLBACK_DELAY_MS = 1_500L
    /** fallback 上传完成后，等待 MCP id 以便回传，避免服务端收不到结果反复播报 */
    private const val WAIT_MCP_REPLY_MS = 8_000L
    private const val WAIT_MCP_POLL_MS = 50L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val photoCaptureInFlight = AtomicBoolean(false)
    private var fallbackJob: Job? = null

    private data class PendingMcpReply(
        val id: Int,
        val question: String,
        val mqtt: MqttUdpManager,
    )

    @Volatile
    private var pendingMcpReply: PendingMcpReply? = null

    /** fallback 已上传成功、等待 MCP tools/call 来取结果 */
    @Volatile
    private var pendingUploadResult: JsonObject? = null

    /** 同一拍照轮次只回传一次完整 MCP 结果，避免服务端重复 TTS */
    private val mcpToolResultSent = AtomicBoolean(false)

    fun isPhotoCaptureInFlight(): Boolean = photoCaptureInFlight.get()

    /** 拍照会话超时或 takePicture 挂死时由 UI 层调用 */
    fun abortStuckCapture(reason: String) {
        cancelTakePhotoFallback()
        pendingMcpReply = null
        pendingUploadResult = null
        mcpToolResultSent.set(false)
        photoCaptureInFlight.set(false)
        SharedCameraCapture.forceReset()
        Log.w(TAG, "中止卡住的拍照 reason=$reason")
    }

    private fun sendToolResultOnce(
        mqtt: MqttUdpManager,
        id: Int,
        toolPayload: JsonObject,
        sessionGeneration: Long,
        reason: String,
    ): Boolean {
        if (!mcpToolResultSent.compareAndSet(false, true)) {
            Log.w(
                TAG,
                "跳过重复 MCP 结果回传 id=$id gen=$sessionGeneration reason=$reason",
            )
            mqtt.sendMcpToolResult(
                id,
                XiaozhiVisionClient.buildToolCallResult("拍照结果已回传"),
            )
            return false
        }
        mqtt.sendMcpToolResult(id, toolPayload)
        Log.i(TAG, "MCP 结果已发送 id=$id gen=$sessionGeneration reason=$reason")
        return true
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
            sessionManager.mqttManager.events.collect { event ->
                if (event is com.powerchina.zhixun.network.MqttUdpEvent.MCPMessage) {
                    handleMcpMessage(sessionManager.mqttManager, event.message)
                }
            }
        }
        Log.i(TAG, "已注册 MCP 处理器")
    }

    private fun handleMcpMessage(mqtt: MqttUdpManager, message: String) {
        try {
            val root = JsonParser.parseString(message).asJsonObject
            val payload = root.getAsJsonObject("payload") ?: return
            val method = payload.get("method")?.asString ?: return
            if (method.startsWith("notifications/")) return

            val id = payload.get("id")?.asInt ?: return
            Log.i(TAG, "收到 MCP method=$method id=$id")
            when (method) {
                "initialize" -> handleInitialize(mqtt, id, payload)
                "tools/list" -> handleToolsList(mqtt, id)
                "tools/call" -> {
                    val name = payload.getAsJsonObject("params")?.get("name")?.asString
                    Log.i(TAG, "★ 服务端调用 tools/call name=$name")
                    handleToolsCall(mqtt, id, payload)
                }
                else -> mqtt.sendMcpError(id, "Method not implemented: $method")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 MCP 失败", e)
        }
    }

    private fun handleInitialize(mqtt: MqttUdpManager, id: Int, payload: JsonObject) {
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
        mqtt.sendMcpInitializeResult(id)
        Log.d(TAG, "MCP initialize 已响应 device=${cfg.macAddress.takeLast(8)}")
    }

    private fun handleToolsList(mqtt: MqttUdpManager, id: Int) {
        mqtt.sendMcpToolsListResult(id)
    }

    private fun handleToolsCall(mqtt: MqttUdpManager, id: Int, payload: JsonObject) {
        val params = payload.getAsJsonObject("params") ?: run {
            mqtt.sendMcpError(id, "Missing params")
            return
        }
        val name = params.get("name")?.asString.orEmpty()
        if (name != TOOL_TAKE_PHOTO) {
            mqtt.sendMcpError(id, "Unknown tool: $name")
            return
        }
        val arguments = params.getAsJsonObject("arguments")
        val question = arguments?.get("question")?.asString?.ifBlank { null } ?: "请描述这张照片"

        // fallback 已上传完：直接回传，避免再次拍照导致服务端重复播报
        val ready = pendingUploadResult
        if (ready != null) {
            pendingUploadResult = null
            cancelTakePhotoFallback()
            val gen = XiaozhiAppEvents.currentPhotoSessionGeneration()
            Log.i(TAG, "MCP tools/call 复用已上传结果 id=$id gen=$gen")
            sendToolResultOnce(mqtt, id, ready, gen, "reuse_pending_upload")
            return
        }

        // 本轮已回传过：应答但不带完整描述，降低二次 TTS
        if (mcpToolResultSent.get()) {
            Log.w(TAG, "tools/call 重复，本轮已回传过 id=$id")
            mqtt.sendMcpToolResult(
                id,
                XiaozhiVisionClient.buildToolCallResult("拍照结果已回传"),
            )
            return
        }

        val app = application ?: run {
            mqtt.sendMcpError(id, "Application not ready")
            return
        }

        scope.launch {
            runTakePhoto(
                app = app,
                mqtt = mqtt,
                mcpId = id,
                question = question,
                trigger = "mcp_tools_call",
            )
        }
    }

    /**
     * 部分服务端经 TTS 下发 take_photo（非 MCP tools/call），收到后本地补拍。
     */
    fun executeTakePhotoFromTtsFallback(trigger: String) {
        val app = application ?: return
        if (!XiaozhiAppEvents.isPhotoSessionActive()) return
        val mqtt = XiaozhiSessionManager.getInstance(app).mqttManager
        scope.launch {
            runTakePhoto(
                app = app,
                mqtt = mqtt,
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
        mqtt: MqttUdpManager,
        mcpId: Int?,
        question: String,
        trigger: String,
    ) {
        if (!photoCaptureInFlight.compareAndSet(false, true)) {
            if (mcpId != null) {
                pendingMcpReply = PendingMcpReply(mcpId, question, mqtt)
                cancelTakePhotoFallback()
                Log.i(TAG, "跳过重复拍照：已在进行中，MCP id=$mcpId 排队取结果 trigger=$trigger")
                return
            }
            Log.w(TAG, "跳过重复拍照：已在进行中 trigger=$trigger mcpId=null")
            return
        }
        Log.i(TAG, "开始拍照上传 detectImageFile trigger=$trigger mcpId=$mcpId")
        cancelTakePhotoFallback()
        mcpToolResultSent.set(false)
        var sessionEngaged = false
        var recoverUi = false
        var recoverMessage: String? = null
        var sessionGeneration = 0L
        var awaitServerTts = true
        var localResultText: String? = null
        var photoFileForLocal: File? = null
        try {
            if (!XiaozhiAppEvents.isPhotoSessionActive()) {
                Log.w(TAG, "忽略无会话 take_photo trigger=$trigger")
                mcpId?.let { mqtt.sendMcpError(it, "无进行中的拍照请求") }
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
            photoFileForLocal = photoFile
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
            val displayText = XiaozhiVisionClient.displayTextFromResult(visionResult)
            val mcpDelivered = if (mcpId != null) {
                sendToolResultOnce(
                    mqtt,
                    mcpId,
                    toolPayload,
                    sessionGeneration,
                    "mcp_direct",
                )
            } else {
                // fallback：先尝试已排队的 MCP；否则短等 tools/call
                if (replyPendingMcp(toolPayload, sessionGeneration)) {
                    true
                } else {
                    pendingUploadResult = toolPayload
                    val sent = waitAndReplyPendingMcp(toolPayload, sessionGeneration)
                    if (!sent) {
                        Log.w(TAG, "上传完成但未等到 MCP id，改走本地展示/播报")
                        pendingUploadResult = null
                    }
                    sent
                }
            }
            if (!mcpDelivered) {
                // 服务端未调 tools/call：无法回传，本地出字+系统 TTS，避免一直「思考中」
                localResultText = displayText.ifBlank { "拍照完成" }
                awaitServerTts = false
                Log.i(TAG, "无 MCP 回传，本地结果: $localResultText")
            }
            Log.i(
                TAG,
                "take_photo 完成 trigger=$trigger mcpId=$mcpId gen=$sessionGeneration " +
                    "mcpDelivered=$mcpDelivered",
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "take_photo 超时 trigger=$trigger mcpId=$mcpId", e)
            SharedCameraCapture.forceReset()
            pendingUploadResult = null
            sendMcpFailure(mqtt, mcpId, "拍照超时，请重试")
            recoverUi = true
            recoverMessage = "拍照超时，请重试"
        } catch (e: Exception) {
            Log.e(TAG, "take_photo 失败 trigger=$trigger mcpId=$mcpId", e)
            pendingUploadResult = null
            val userMessage = formatPhotoUploadError(e)
            sendMcpFailure(mqtt, mcpId, userMessage)
            recoverUi = true
            recoverMessage = userMessage
        } finally {
            photoCaptureInFlight.set(false)
            if (sessionEngaged) {
                val localText = localResultText
                if (localText != null) {
                    XiaozhiAppEvents.emitPhotoResult(
                        PhotoResult(
                            file = photoFileForLocal,
                            uploadResult = Result.success(Unit),
                            captureOnly = false,
                            localResultText = localText,
                        ),
                    )
                }
                XiaozhiAppEvents.endPhotoSession(
                    recoverUi = recoverUi,
                    recoverMessage = recoverMessage,
                    sessionGeneration = sessionGeneration,
                    awaitServerTts = awaitServerTts && !recoverUi,
                )
            }
        }
    }

    private suspend fun waitAndReplyPendingMcp(
        toolPayload: JsonObject,
        sessionGeneration: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MCP_REPLY_MS
        while (System.currentTimeMillis() < deadline) {
            if (replyPendingMcp(toolPayload, sessionGeneration)) {
                pendingUploadResult = null
                return true
            }
            // handleToolsCall 可能已直接取走 pendingUploadResult 并回传
            if (pendingUploadResult == null) return true
            delay(WAIT_MCP_POLL_MS)
        }
        return false
    }

    private fun ensureCameraPermission(app: Application) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("需要相机权限，请在对话页允许相机访问")
        }
    }

    private fun replyPendingMcp(
        toolPayload: JsonObject,
        sessionGeneration: Long,
    ): Boolean {
        val pending = pendingMcpReply ?: return false
        pendingMcpReply = null
        sendToolResultOnce(
            pending.mqtt,
            pending.id,
            toolPayload,
            sessionGeneration,
            "queued_reply",
        )
        return true
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

    private fun sendMcpFailure(mqtt: MqttUdpManager, mcpId: Int?, message: String) {
        if (mcpId != null) {
            mqtt.sendMcpError(mcpId, message)
            return
        }
        val pending = pendingMcpReply ?: return
        pendingMcpReply = null
        pending.mqtt.sendMcpError(pending.id, message)
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
