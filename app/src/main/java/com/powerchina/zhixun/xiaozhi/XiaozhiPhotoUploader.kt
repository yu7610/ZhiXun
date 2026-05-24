package com.powerchina.zhixun.xiaozhi

import android.app.Application
import android.util.Log
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.network.WebSocketManager
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 将执法仪照片压缩后上传隐患检测 HTTP 接口，并将结果发送到小智对话。
 */
object XiaozhiPhotoUploader {

    private const val TAG = "XiaozhiPhoto"
    private const val CONNECT_WAIT_MS = 15_000L

    suspend fun uploadPhoto(
        application: Application,
        photoFile: File,
        prompt: String = "请描述这张照片",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val visionResult = uploadPhotoForMcp(application, photoFile, prompt).getOrThrow()
            val sessionManager = XiaozhiSessionManager.getInstance(application)
            val webSocket = sessionManager.webSocketManager
            waitForConnection(webSocket)
            val displayText = XiaozhiVisionClient.displayTextFromResult(visionResult)
            webSocket.sendVisionQuery(displayText)
            Log.i(TAG, "已发送视觉结果到对话 len=${displayText.length}")
            displayText
        }.onFailure { e ->
            Log.e(TAG, "上传照片失败", e)
            XiaozhiAppEvents.endPhotoSession()
        }
    }

    suspend fun uploadPhotoForMcp(
        application: Application,
        photoFile: File,
        @Suppress("UNUSED_PARAMETER") prompt: String = "请描述这张照片",
    ): Result<VisionExplainResult> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionManager = XiaozhiSessionManager.getInstance(application)
            sessionManager.ensureConnected()
            val webSocket = sessionManager.webSocketManager
            waitForConnection(webSocket)

            var jpegBytes = compressJpegForUpload(photoFile)
            if (jpegBytes.size > 180_000) {
                jpegBytes = compressJpegForUpload(photoFile, maxWidth = 480, quality = 65)
            }
            if (jpegBytes.size > 180_000) {
                throw IllegalStateException("照片过大(${jpegBytes.size} bytes)，请靠近拍摄")
            }

            val macAddress = ConfigManager(application).loadConfig().macAddress
            Log.i(
                TAG,
                "上传照片 ${photoFile.name} size=${jpegBytes.size} bytes session=${webSocket.getSessionId()}",
            )

            XiaozhiAppEvents.beginPhotoSession()
            XiaozhiWakeForegroundService.pauseListening(application)
            webSocket.sendStopListening()
            delay(250)

            XiaozhiVisionClient.detectImageFile(
                context = application,
                deviceId = macAddress,
                jpegBytes = jpegBytes,
                filename = photoFile.name,
            ).getOrThrow()
        }.onFailure { e ->
            Log.e(TAG, "视觉上传失败", e)
        }
    }

    private suspend fun waitForConnection(webSocketManager: WebSocketManager) {
        val deadline = System.currentTimeMillis() + CONNECT_WAIT_MS
        while (!webSocketManager.isConnected()) {
            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("小智未连接，请检查网络与配置")
            }
            delay(100)
        }
    }

    fun compressJpegForUpload(
        source: File,
        maxWidth: Int = 640,
        quality: Int = 75,
    ): ByteArray {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("无法读取照片")
        }

        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxWidth)
        }
        var bitmap = android.graphics.BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
            ?: throw IllegalStateException("照片解码失败")

        if (bitmap.width > maxWidth) {
            val targetHeight = kotlin.math.max(
                1,
                (bitmap.height * maxWidth.toFloat() / bitmap.width).toInt(),
            )
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
            if (scaled != bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        return java.io.ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)) {
                bitmap.recycle()
                throw IllegalStateException("照片压缩失败")
            }
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxWidth: Int): Int {
        var sampleSize = 1
        var currentWidth = width
        while (currentWidth > maxWidth * 2) {
            sampleSize *= 2
            currentWidth /= 2
        }
        return sampleSize
    }
}
