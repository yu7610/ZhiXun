package com.powerchina.zhixun.dashcam

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 录屏结束后二次压缩：降分辨率 + H.264 转码，保留音轨，替换原文件。
 */
object DashcamVideoCompressor {

    const val TAG = "DashcamCompress"
    /** 压缩目标高度（原片多为 720p HD） */
    private const val TARGET_HEIGHT_PX = 480

    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun compressAndReplace(context: Context, source: File): Result<CompressResult> {
        if (!source.exists() || source.length() == 0L) {
            return Result.failure(IllegalStateException("源视频不存在或为空"))
        }
        val tempOutput = DashcamRecordingStore.createCompressTempFile(source)
        tempOutput.delete()
        val originalBytes = source.length()
        Log.i(TAG, "开始压缩 ${source.name} size=${originalBytes}B → 高度≤${TARGET_HEIGHT_PX}p")

        val transcode = transcodeToFile(context, source, tempOutput)
        if (transcode.isFailure) {
            tempOutput.delete()
            return Result.failure(
                transcode.exceptionOrNull() ?: IllegalStateException("压缩失败"),
            )
        }

        val compressedBytes = tempOutput.length()
        if (compressedBytes <= 0L) {
            tempOutput.delete()
            return Result.failure(IllegalStateException("压缩输出为空"))
        }
        if (!source.delete()) {
            tempOutput.delete()
            return Result.failure(IllegalStateException("无法删除原视频"))
        }
        if (!tempOutput.renameTo(source)) {
            return Result.failure(IllegalStateException("无法替换为压缩文件"))
        }

        val saved = originalBytes - compressedBytes
        val ratio = if (originalBytes > 0) compressedBytes * 100 / originalBytes else 100L
        Log.i(
            TAG,
            "压缩完成 ${source.name} ${originalBytes}B → ${compressedBytes}B " +
                "节省${saved}B (${ratio}%)",
        )
        return Result.success(
            CompressResult(
                file = source,
                originalBytes = originalBytes,
                compressedBytes = compressedBytes,
            ),
        )
    }

    private suspend fun transcodeToFile(
        context: Context,
        source: File,
        output: File,
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val app = context.applicationContext
            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(Presentation.createForHeight(TARGET_HEIGHT_PX)),
                    ),
                )
                .build()

            val transformer = Transformer.Builder(app)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (cont.isActive) cont.resume(Result.success(Unit))
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            Log.e(TAG, "Transformer 失败", exception)
                            if (cont.isActive) cont.resume(Result.failure(exception))
                        }
                    },
                )
                .build()

            cont.invokeOnCancellation {
                mainHandler.post {
                    runCatching { transformer.cancel() }
                        .onFailure { Log.w(TAG, "Transformer cancel 失败", it) }
                }
            }
            transformer.start(editedMediaItem, output.absolutePath)
        }
    }
}

data class CompressResult(
    val file: File,
    val originalBytes: Long,
    val compressedBytes: Long,
)
