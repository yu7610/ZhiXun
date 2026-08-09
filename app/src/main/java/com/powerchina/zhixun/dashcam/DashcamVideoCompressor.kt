package com.powerchina.zhixun.dashcam

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
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
 * 录屏结束后二次压缩：降分辨率 + H.264 转码。
 * 压缩片写入 sdcard0/DCIM/100MEDIA，校验通过后覆盖原片 yyyyMMddHHmmss-00N.MP4。
 */
object DashcamVideoCompressor {

    const val TAG = "DashcamCompress"
    /** 压缩目标高度（原片多为 720p HD） */
    private const val TARGET_HEIGHT_PX = 480

    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun compressToSdcard0(
        context: Context,
        source: File,
        originalRecordingUri: Uri,
    ): Result<CompressResult> {
        if (!source.exists() || source.length() == 0L) {
            return Result.failure(
                IllegalStateException("源视频不存在或为空 path=${source.absolutePath}"),
            )
        }
        val tempOutput = File(
            context.cacheDir,
            "dashcam_compress_${System.currentTimeMillis()}.mp4",
        )
        tempOutput.delete()
        val originalBytes = source.length()
        Log.i(
            TAG,
            "开始压缩 ${source.name} size=${originalBytes}B → 高度≤${TARGET_HEIGHT_PX}p → sdcard0",
        )

        val transcode = transcodeToFile(context, source, tempOutput)
        if (transcode.isFailure) {
            tempOutput.delete()
            return Result.failure(
                transcode.exceptionOrNull() ?: IllegalStateException("压缩失败"),
            )
        }

        val tempCompressedBytes = tempOutput.length()
        if (tempCompressedBytes <= 0L) {
            tempOutput.delete()
            return Result.failure(IllegalStateException("压缩输出为空"))
        }

        val saveResult = withContext(Dispatchers.IO) {
            DashcamRecordingStore.saveCompressedVideoToSdcard0(
                context = context,
                compressedTemp = tempOutput,
                displayName = source.name,
                originalRecordingUri = originalRecordingUri,
                originalSourceFile = source,
            )
        }
        tempOutput.delete()
        val sdcard0File = saveResult?.file
        val compressedBytes = sdcard0File?.let { DashcamRecordingStore.fileSizeOnDevice(it) } ?: 0L
        if (sdcard0File == null || compressedBytes == 0L) {
            return Result.failure(IllegalStateException("压缩片写入 sdcard0 失败"))
        }

        val saved = originalBytes - compressedBytes
        val ratio = if (originalBytes > 0) compressedBytes * 100 / originalBytes else 100L
        Log.i(
            TAG,
            "压缩完成 ${source.name} ${originalBytes}B → ${compressedBytes}B " +
                "节省${saved}B (${ratio}%) sdcard0=${sdcard0File.absolutePath} " +
                "sameAlias=${saveResult.sameVolumeAlias}",
        )
        return Result.success(
            CompressResult(
                sourceFile = saveResult.emulatedFile,
                sdcard0File = sdcard0File,
                originalBytes = originalBytes,
                compressedBytes = compressedBytes,
                sameVolumeAlias = saveResult.sameVolumeAlias,
            ),
        )
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcodeToFile(
        context: Context,
        source: File,
        output: File,
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val app = context.applicationContext
            val sourceUri = source.toUri()
            val watermarkStart = DashcamVideoWatermark.formatRecordingTimestamp(source.name)
            val videoEffects = buildList {
                add(Presentation.createForHeight(TARGET_HEIGHT_PX))
                DashcamVideoWatermark.createOverlayEffect(source.name)?.let { add(it) }
            }
            Log.i(TAG, "转码加水印 ${source.name} start=$watermarkStart（随播放进度逐秒变化）")
            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
                .setEffects(
                    Effects(
                        emptyList(),
                        videoEffects,
                    ),
                )
                .build()

            val transformer = Transformer.Builder(app)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
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
                            Log.e(
                                TAG,
                                "Transformer 失败 source=${source.absolutePath} " +
                                    "code=${exception.errorCode} msg=${exception.message}",
                                exception,
                            )
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
    val sourceFile: File,
    val sdcard0File: File,
    val originalBytes: Long,
    val compressedBytes: Long,
    val sameVolumeAlias: Boolean,
)
