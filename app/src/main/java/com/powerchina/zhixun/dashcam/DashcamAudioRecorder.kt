package com.powerchina.zhixun.dashcam

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 执法仪独立录音（与录像互斥，仅在未录像时使用麦克风）。
 */
class DashcamAudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean
        get() = recorder != null

    fun start(outputFile: File): Result<Unit> = runCatching {
        require(!isRecording) { "已在录音" }
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder.setAudioSamplingRate(16_000)
        mediaRecorder.setAudioEncodingBitRate(64_000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaRecorder.setAudioChannels(1)
        }
        mediaRecorder.setOutputFile(outputFile.absolutePath)
        mediaRecorder.prepare()
        mediaRecorder.start()
        recorder = mediaRecorder
        this.outputFile = outputFile
        Log.i(TAG, "开始录音 path=${outputFile.absolutePath}")
    }

    fun stop(): Result<File> = runCatching {
        val mediaRecorder = recorder ?: throw IllegalStateException("未在录音")
        val file = outputFile ?: throw IllegalStateException("无输出文件")
        mediaRecorder.stop()
        mediaRecorder.release()
        recorder = null
        outputFile = null
        require(file.exists() && file.length() > 0L) { "录音文件为空" }
        Log.i(TAG, "停止录音 path=${file.absolutePath} size=${file.length()}B")
        file
    }

    fun release() {
        runCatching {
            recorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {
                }
                release()
            }
        }
        recorder = null
        outputFile = null
    }

    companion object {
        const val TAG = "DashcamAudio"
    }
}
