package com.powerchina.zhixun.dashcam

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.VideoCapture
import androidx.camera.video.Recorder
import java.io.File
import java.util.concurrent.Executor

/**
 * 执法拍摄相机会话：预览 + 录像 + 拍照。
 */
class DashcamCameraSession(
    private val context: Context,
    private val imageCapture: ImageCapture,
    val recordingController: DashcamRecordingController,
    private val mainExecutor: Executor,
) {
    fun takePicture(outputFile: File, onResult: (Result<File>) -> Unit) {
        val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        imageCapture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onResult(Result.success(outputFile))
                }

                override fun onError(exception: ImageCaptureException) {
                    onResult(Result.failure(exception))
                }
            },
        )
    }
}
