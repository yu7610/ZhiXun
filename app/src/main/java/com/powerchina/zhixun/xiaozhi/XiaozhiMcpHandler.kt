package com.powerchina.zhixun.xiaozhi

import android.app.Application
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.powerchina.zhixun.dashcam.SharedCameraCapture
import com.powerchina.zhixun.network.WebSocketManager
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 作为 MCP 服务端响应小智云端（initialize / tools/list / tools/call）。
 */
object XiaozhiMcpHandler {

    private const val TAG = "XiaozhiMcp"
    private const val TOOL_TAKE_PHOTO = "self.camera.take_photo"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            when (method) {
                "initialize" -> handleInitialize(webSocket, id, payload)
                "tools/list" -> handleToolsList(webSocket, id)
                "tools/call" -> handleToolsCall(webSocket, id, payload)
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
            XiaozhiAppEvents.beginPhotoSession()
            XiaozhiWakeForegroundService.pauseListening(app)
            try {
                val photoFile = capturePhoto(app) ?: throw IllegalStateException("拍照失败")
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
                webSocket.sendMcpToolResult(
                    id,
                    XiaozhiVisionClient.buildToolCallResult(visionResult.response),
                )
                XiaozhiAppEvents.emitPhotoResult(
                    PhotoResult(
                        file = photoFile,
                        uploadResult = Result.success(Unit),
                        visionDescription = XiaozhiVisionClient.displayTextFromResult(visionResult),
                    ),
                )
                Log.i(TAG, "MCP tools/call 拍照完成 question=$question")
            } catch (e: Exception) {
                Log.e(TAG, "MCP tools/call 失败", e)
                webSocket.sendMcpError(id, e.message ?: "拍照失败")
                XiaozhiAppEvents.endPhotoSession()
            }
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
            }
        }
}
