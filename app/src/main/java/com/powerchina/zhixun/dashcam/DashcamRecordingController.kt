package com.powerchina.zhixun.dashcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import java.util.concurrent.Executor

class DashcamRecordingController(
    private val context: Context,
    private val videoCapture: VideoCapture<Recorder>,
    private val mainExecutor: Executor,
) {
    private val tag = "DashcamRecCtrl"

    private var activeRecording: Recording? = null
    private var currentRequest: DashcamVideoOutputRequest? = null
    private var onFinalize: ((Result<DashcamVideoOutput>) -> Unit)? = null

    val isRecording: Boolean
        get() = activeRecording != null

    fun startRecording(
        request: DashcamVideoOutputRequest,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (activeRecording != null) {
            Log.w(tag, "startRecording 跳过：已在录制 name=${request.displayName}")
            return false
        }
        currentRequest = request
        onFinalize = null
        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            DashcamRecordingStore.videoCollectionUri(),
        )
            .setContentValues(request.contentValues)
            .build()
        val pendingRecording = videoCapture.output.prepareRecording(context, outputOptions)
        val withAudio = if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            pendingRecording.withAudioEnabled()
        } else {
            Log.w(tag, "无麦克风权限，录像不含音频")
            pendingRecording
        }
        activeRecording = withAudio.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.i(tag, "录像已开始 name=${request.displayName}")
                        onStarted()
                    }
                    is VideoRecordEvent.Finalize -> {
                        val videoRequest = currentRequest
                        activeRecording = null
                        currentRequest = null
                        val callback = onFinalize
                        onFinalize = null
                        if (event.hasError()) {
                            val failedUri = event.outputResults.outputUri
                            Log.e(
                                tag,
                                "录像 Finalize 失败 name=${videoRequest?.displayName} " +
                                    "uri=$failedUri error=${event.cause?.message}",
                                event.cause,
                            )
                            if (failedUri != null && videoRequest != null) {
                                DashcamRecordingStore.deleteVideoOutput(
                                    context,
                                    DashcamVideoOutput(failedUri, videoRequest.displayName),
                                )
                            }
                            callback?.invoke(
                                Result.failure(Exception(event.cause?.message ?: "录像失败")),
                            )
                            onError(event.cause?.message ?: "录像失败")
                        } else if (videoRequest != null) {
                            val outputUri = event.outputResults.outputUri
                            val output = DashcamVideoOutput(outputUri, videoRequest.displayName)
                            Log.i(
                                tag,
                                "录像 Finalize 成功 uri=$outputUri " +
                                    "durationNs=${event.recordingStats.recordedDurationNanos}",
                            )
                            callback?.invoke(Result.success(output))
                        }
                    }
                }
            }
        return true
    }

    fun stopRecording(onStopped: (Result<DashcamVideoOutput>) -> Unit) {
        val recording = activeRecording ?: run {
            onStopped(Result.failure(IllegalStateException("未在录制")))
            return
        }
        onFinalize = onStopped
        recording.stop()
    }
}
