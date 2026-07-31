package com.powerchina.zhixun.xiaozhi

import android.app.Application
import android.util.Log
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.physicalkey.PhotoKeyLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP 拍照：压缩后上传隐患检测 HTTP 接口（detectImageFile），
 * 结果由调用方通过 sendMcpToolResult 回传小智。
 *
 * 注意：上传走独立 HTTP，不依赖 MQTT/UDP 握手。
 */
object XiaozhiPhotoUploader {

    private const val TAG = PhotoKeyLog.TAG

    suspend fun uploadPhotoForMcp(
        application: Application,
        photoFile: File,
        @Suppress("UNUSED_PARAMETER") prompt: String = "请描述这张照片",
    ): Result<VisionExplainResult> = withContext(Dispatchers.IO) {
        runCatching {
            var jpegBytes = compressJpegForUpload(photoFile, maxWidth = 480, quality = 70)
            if (jpegBytes.size > 180_000) {
                jpegBytes = compressJpegForUpload(photoFile, maxWidth = 360, quality = 60)
            }
            if (jpegBytes.size > 180_000) {
                throw IllegalStateException("照片过大(${jpegBytes.size} bytes)，请靠近拍摄")
            }

            val macAddress = ConfigManager(application).loadConfig().macAddress
            Log.i(
                TAG,
                "上传照片到 detectImageFile ${photoFile.name} size=${jpegBytes.size} bytes",
            )

            XiaozhiVisionClient.detectImageFile(
                context = application,
                deviceId = macAddress,
                jpegBytes = jpegBytes,
                filename = photoFile.name,
            ).getOrThrow()
        }.onFailure { e ->
            Log.e(TAG, "MCP 视觉上传失败", e)
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
