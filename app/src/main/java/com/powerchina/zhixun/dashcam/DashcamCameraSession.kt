package com.powerchina.zhixun.dashcam

import android.content.Context
import java.io.File
import java.util.concurrent.Executor

/**
 * 执法拍摄会话：RootEncoder 预览/录像/推流 + 抓拍。
 */
class DashcamCameraSession(
    private val context: Context,
    private val engine: DashcamRtspEngine,
    val recordingController: DashcamRecordingController,
    private val mainExecutor: Executor,
) {
    fun takePicture(outputFile: File, onResult: (Result<File>) -> Unit) {
        val silencer = SilentImageCapture.muteForCapture(context)
        engine.takePicture(outputFile) { result ->
            silencer.restore()
            mainExecutor.execute { onResult(result) }
        }
    }
}
