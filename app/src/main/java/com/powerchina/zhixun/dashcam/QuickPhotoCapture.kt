package com.powerchina.zhixun.dashcam

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.util.concurrent.Executor

/**
 * 无预览界面快速拍照（绑定 Process 生命周期，供聊天页物理键使用）。
 */
object QuickPhotoCapture {

    private const val TAG = "QuickPhotoCapture"

    fun capture(context: Context, onResult: (Result<File>) -> Unit) {
        val appContext = context.applicationContext
        val mainExecutor = ContextCompat.getMainExecutor(appContext)
        val lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get()
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            { runCapture(appContext, future.get(), lifecycleOwner, mainExecutor, onResult) },
            mainExecutor,
        )
    }

    private fun runCapture(
        appContext: Context,
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        mainExecutor: Executor,
        onResult: (Result<File>) -> Unit,
    ) {
        try {
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageCapture,
            )
            val file = DashcamRecordingStore.createPhotoFile(appContext)
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            imageCapture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        cameraProvider.unbindAll()
                        Log.i(TAG, "拍照成功 ${file.name}")
                        onResult(Result.success(file))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cameraProvider.unbindAll()
                        Log.e(TAG, "拍照失败", exception)
                        onResult(Result.failure(exception))
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "相机初始化失败", e)
            onResult(Result.failure(e))
        }
    }
}
