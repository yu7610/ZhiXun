package com.powerchina.zhixun.dashcam

import android.content.Context
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import java.io.File
import java.util.concurrent.Executor

class DashcamRecordingController(
    private val context: Context,
    private val videoCapture: VideoCapture<Recorder>,
    private val mainExecutor: Executor,
) {
    private var activeRecording: Recording? = null
    private var currentFile: File? = null
    private var onFinalize: ((Result<File>) -> Unit)? = null

    val isRecording: Boolean
        get() = activeRecording != null

    fun startRecording(
        outputFile: File,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (activeRecording != null) return
        currentFile = outputFile
        onFinalize = null
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        // 录像不采集麦克风
        activeRecording = videoCapture.output
            .prepareRecording(context, outputOptions)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> onStarted()
                    is VideoRecordEvent.Finalize -> {
                        val file = currentFile
                        activeRecording = null
                        currentFile = null
                        val callback = onFinalize
                        onFinalize = null
                        if (event.hasError()) {
                            file?.delete()
                            callback?.invoke(Result.failure(Exception(event.cause?.message ?: "录像失败")))
                            onError(event.cause?.message ?: "录像失败")
                        } else if (file != null) {
                            callback?.invoke(Result.success(file))
                        }
                    }
                }
            }
    }

    fun stopRecording(onStopped: (Result<File>) -> Unit) {
        val recording = activeRecording ?: run {
            onStopped(Result.failure(IllegalStateException("未在录制")))
            return
        }
        onFinalize = onStopped
        recording.stop()
    }
}
