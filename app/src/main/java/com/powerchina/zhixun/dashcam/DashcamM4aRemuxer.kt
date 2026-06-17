package com.powerchina.zhixun.dashcam

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 将 MediaRecorder 产出的 m4a 重新封装为标准 MP4 容器，便于服务端解码。
 * 不重新编码，仅 remux。
 */
object DashcamM4aRemuxer {

    private val logTag get() = DashcamAsrUploader.TAG

    fun remux(source: File, output: File): Result<File> = runCatching {
        require(source.exists() && source.length() > 0L) { "源音频为空" }
        if (output.exists()) {
            output.delete()
        }
        Log.i(logTag, "remux m4a ${source.name} (${source.length()}B) → ${output.name}")

        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)

        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrack = i
                format = trackFormat
                break
            }
        }
        if (audioTrack < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("未找到音频轨")
        }

        extractor.selectTrack(audioTrack)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrack = muxer.addTrack(format)
        muxer.start()

        val buffer = ByteBuffer.allocate(256 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                break
            }
            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
            extractor.advance()
        }

        muxer.stop()
        muxer.release()
        extractor.release()

        require(output.exists() && output.length() > 0L) { "remux 输出为空" }
        Log.i(logTag, "remux 完成 ${output.name} size=${output.length()}B")
        output
    }.onFailure { e ->
        Log.e(logTag, "remux 失败", e)
    }
}
