package com.powerchina.zhixun.dashcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 执法仪录像本地落盘水印：显示录制日期与时间。
 */
object DashcamVideoWatermark {

    private const val TAG = DashcamVideoCompressor.TAG
    /** 480p 成片上的水印字号（像素） */
    private const val WATERMARK_TEXT_SIZE_PX = 28f

    fun formatRecordingTimestamp(fileName: String): String {
        val stem = DashcamRecordingStore.parseOemMp4Stem(fileName)
        val recordedAt = runCatching {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(stem)
        }.getOrNull() ?: Date()
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(recordedAt)
    }

    @OptIn(UnstableApi::class)
    fun createOverlayEffect(watermarkText: String): OverlayEffect? {
        return try {
            val bitmap = createWatermarkBitmap(watermarkText)
            val settings = StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(-1f, -1f)
                .setOverlayFrameAnchor(-1f, -1f)
                .setAlphaScale(0.92f)
                .build()
            val overlay: TextureOverlay =
                BitmapOverlay.createStaticBitmapOverlay(bitmap, settings)
            OverlayEffect(listOf(overlay))
        } catch (e: Exception) {
            Log.w(TAG, "创建视频水印失败: $watermarkText", e)
            null
        }
    }

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
}
