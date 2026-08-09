package com.powerchina.zhixun.dashcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 执法仪录像本地落盘水印：日期时间随画面播放进度逐秒变化。
 */
object DashcamVideoWatermark {

    private const val TAG = DashcamVideoCompressor.TAG
    /** 480p 成片上的水印字号（像素） */
    private const val WATERMARK_TEXT_SIZE_PX = 28f

    fun formatRecordingTimestamp(fileName: String): String {
        val recordedAt = Date(parseRecordingStartEpochMs(fileName))
        return displayFormat().format(recordedAt)
    }

    /** 从文件名解析录制起点（毫秒 epoch）；失败则用当前时间 */
    fun parseRecordingStartEpochMs(fileName: String): Long {
        val stem = DashcamRecordingStore.parseOemMp4Stem(fileName)
        return runCatching {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(stem)?.time
        }.getOrNull() ?: System.currentTimeMillis()
    }

    /**
     * 动态水印：presentationTimeUs 对应「录制起点 + 播放进度」，每秒更新一次文字。
     */
    @OptIn(UnstableApi::class)
    fun createOverlayEffect(fileName: String): OverlayEffect? {
        return try {
            val startEpochMs = parseRecordingStartEpochMs(fileName)
            val settings = StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(-1f, -1f)
                .setOverlayFrameAnchor(-1f, -1f)
                .setAlphaScale(0.92f)
                .build()
            val overlay: TextureOverlay = TimeTickingBitmapOverlay(
                recordingStartEpochMs = startEpochMs,
                overlaySettings = settings,
            )
            OverlayEffect(listOf(overlay))
        } catch (e: Exception) {
            Log.w(TAG, "创建视频水印失败: $fileName", e)
            null
        }
    }

    private fun displayFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun createWatermarkBitmap(text: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = WATERMARK_TEXT_SIZE_PX
            isFakeBoldText = true
            setShadowLayer(2f, 1f, 1f, Color.argb(180, 0, 0, 0))
        }
        val paddingH = 10
        val paddingV = 6
        val textWidth = paint.measureText(text)
        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val width = (textWidth + paddingH * 2).toInt().coerceAtLeast(1)
        val height = (textHeight + paddingV * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.argb(96, 0, 0, 0))
        canvas.drawText(
            text,
            paddingH.toFloat(),
            paddingV - fontMetrics.ascent,
            paint,
        )
        return bitmap
    }

    @OptIn(UnstableApi::class)
    private class TimeTickingBitmapOverlay(
        private val recordingStartEpochMs: Long,
        private val overlaySettings: OverlaySettings,
    ) : BitmapOverlay() {

        private val format = displayFormat()
        private var cachedSecondIndex = Long.MIN_VALUE
        private var cachedBitmap: Bitmap? = null

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val secondIndex = presentationTimeUs.coerceAtLeast(0L) / 1_000_000L
            cachedBitmap?.let { cached ->
                if (cachedSecondIndex == secondIndex && !cached.isRecycled) {
                    return cached
                }
            }
            val wallClockMs = recordingStartEpochMs + secondIndex * 1000L
            val text = format.format(Date(wallClockMs))
            val bitmap = createWatermarkBitmap(text)
            cachedSecondIndex = secondIndex
            cachedBitmap = bitmap
            return bitmap
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            overlaySettings
    }
}
