package com.powerchina.zhixun.xiaozhi

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import androidx.core.content.ContextCompat
import com.powerchina.zhixun.dashcam.SharedCameraCapture
import com.powerchina.zhixun.network.WebSocketManager
import com.powerchina.zhixun.physicalkey.PhotoKeyLog
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val photoCaptureInFlight = AtomicBoolean(false)

    /** 拍照会话超时或 takePicture 挂死时由 UI 层调用 */
    fun abortStuckCapture(reason: String) {
        photoCaptureInFlight.set(false)
        SharedCameraCapture.forceReset()
        Log.w(TAG, "中止卡住的拍照 reason=$reason")
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
            if (!photoCaptureInFlight.compareAndSet(false, true)) {
                Log.w(TAG, "忽略并发 MCP take_photo id=$id")
                webSocket.sendMcpError(id, "拍照进行中，请稍候")
                return@launch
            }
            var sessionEngaged = false
            var recoverUi = false
            var recoverMessage: String? = null
            var sessionGeneration = 0L
            try {
                if (!XiaozhiAppEvents.isPhotoSessionActive()) {
                    Log.w(
                        TAG,
                        "忽略无会话 MCP take_photo id=$id（未按拍照键或上一轮已结束）",
                    )
                    webSocket.sendMcpError(id, "无进行中的拍照请求")
                    return@launch
                }
                sessionEngaged = true
                sessionGeneration = XiaozhiAppEvents.currentPhotoSessionGeneration()
                XiaozhiWakeForegroundService.pauseListening(app)
                ensureCameraPermission(app)
                val photoFile = withTimeout(PHOTO_CAPTURE_TIMEOUT_MS) {
                    capturePhoto(app) ?: throw IllegalStateException("拍照失败，请检查相机权限")
                }
                val upload = XiaozhiPhotoUploader.uploadPhotoForMcp(
                    application = app,
                    photoFile = photoFile,
                    prompt = question,
                )
                val visionResult = upload.getOrThrow()
                webSocket.sendMcpToolResult(
                    id,
                    XiaozhiVisionClient.buildToolCallResult(visionResult.response),
                )
                Log.i(TAG, "MCP tools/call 拍照完成 question=$question id=$id gen=$sessionGeneration")
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "MCP 拍照超时 id=$id", e)
                SharedCameraCapture.forceReset()
                webSocket.sendMcpError(id, "拍照超时，请重试")
                recoverUi = true
                recoverMessage = "拍照超时，请重试"
            } catch (e: Exception) {
                Log.e(TAG, "MCP tools/call 失败 id=$id", e)
                webSocket.sendMcpError(id, e.message ?: "拍照失败")
                recoverUi = true
                recoverMessage = e.message ?: "拍照失败"
            } finally {
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
    }

    private fun ensureCameraPermission(app: Application) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("需要相机权限，请在对话页允许相机访问")
        }
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
