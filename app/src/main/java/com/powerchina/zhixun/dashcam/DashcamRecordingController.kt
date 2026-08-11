package com.powerchina.zhixun.dashcam

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * 基于 [DashcamRtspEngine] 的本地录像控制（与 RTSP 推流共用一路相机编码）。
 */
class DashcamRecordingController(
    private val context: Context,
    private val engine: DashcamRtspEngine,
    private val mainExecutor: Executor,
) {
    private val tag = "DashcamRecCtrl"

    private val activeFile = AtomicReference<File?>(null)
    private var currentRequest: DashcamVideoOutputRequest? = null
    private var onFinalize: ((Result<DashcamVideoOutput>) -> Unit)? = null

    val isRecording: Boolean
        get() = engine.isRecording

    fun startRecording(
        request: DashcamVideoOutputRequest,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (engine.isRecording) {
            Log.w(tag, "startRecording 跳过：已在录制 name=${request.displayName}")
            return false
        }
        currentRequest = request
        onFinalize = null
        val recordFile = DashcamRecordingStore.createEncoderRecordFile(context, request.displayName)
        activeFile.set(recordFile)
        Log.i(tag, "开始 RootEncoder 录像 path=${recordFile.absolutePath}")

        return engine.startRecording(
            recordFile = recordFile,
            onStarted = {
                mainExecutor.execute { onStarted() }
            },
            onError = { msg ->
                mainExecutor.execute {
                    activeFile.set(null)
                    currentRequest = null
                    onError(msg)
                }
            },
            onStopped = { result ->
                mainExecutor.execute {
                    val videoRequest = currentRequest
                    currentRequest = null
                    val callback = onFinalize
                    onFinalize = null
                    activeFile.set(null)
                    if (callback == null) {
                        // stop 未登记回调时（异常路径），仅打日志
                        result.onFailure { Log.e(tag, "录像结束但无回调", it) }
                        return@execute
                    }
                    if (videoRequest == null) {
                        callback(Result.failure(IllegalStateException("录像请求丢失")))
                        return@execute
                    }
                    result.fold(
                        onSuccess = { file ->
                            val output = DashcamVideoOutput(
                                uri = Uri.fromFile(file),
                                displayName = videoRequest.displayName,
                            )
                            Log.i(tag, "录像成功 uri=${output.uri} size=${file.length()}B")
                            callback(Result.success(output))
                        },
                        onFailure = { err ->
                            Log.e(tag, "录像失败 name=${videoRequest.displayName}", err)
                            callback(Result.failure(err))
                        },
                    )
                }
            },
        )
    }

    fun stopRecording(onStopped: (Result<DashcamVideoOutput>) -> Unit) {
        if (!engine.isRecording) {
            onStopped(Result.failure(IllegalStateException("未在录制")))
            return
        }
        onFinalize = onStopped
        engine.stopRecording()
    }
}
