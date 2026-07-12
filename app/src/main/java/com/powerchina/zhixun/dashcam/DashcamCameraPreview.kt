package com.powerchina.zhixun.dashcam

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun DashcamCameraPreview(
    lensFacing: Int,
    modifier: Modifier = Modifier,
    onSessionReady: (DashcamCameraSession?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var bindGeneration by remember { mutableIntStateOf(0) }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    runCatching { cameraProviderRef?.unbindAll() }
                    onSessionReady(null)
                }
                Lifecycle.Event.ON_RESUME -> {
                    bindGeneration++
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onSessionReady(null)
            runCatching { cameraProviderRef?.unbindAll() }
            cameraProviderRef = null
        }
    }

    LaunchedEffect(lensFacing, bindGeneration) {
        McpCameraHolder.pauseForDashcam()
        val cameraProvider = try {
            withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 CameraProvider 失败", e)
            onSessionReady(null)
            return@LaunchedEffect
        }
        if (!isActive) return@LaunchedEffect
        cameraProviderRef = cameraProvider
        try {
            bindCamera(
                context = context,
                cameraProvider = cameraProvider,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                lensFacing = lensFacing,
                mainExecutor = mainExecutor,
                onSessionReady = onSessionReady,
            )
        } catch (e: Exception) {
            Log.e(TAG, "绑定相机失败", e)
            onSessionReady(null)
        }
    }

}

private const val TAG = "DashcamCamera"

private fun bindCamera(
    context: Context,
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    lensFacing: Int,
    mainExecutor: Executor,
    onSessionReady: (DashcamCameraSession?) -> Unit,
) {
    val preview = Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }
    val imageCapture = SilentImageCapture.build()
    val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HD))
        .build()
    val videoCapture = VideoCapture.withOutput(recorder)
    val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(lensFacing)
        .build()

    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(
        lifecycleOwner,
        cameraSelector,
        preview,
        imageCapture,
        videoCapture,
    )
    val recordingController = DashcamRecordingController(
        context = context,
        videoCapture = videoCapture,
        mainExecutor = mainExecutor,
    )
    onSessionReady(
        DashcamCameraSession(
            context = context,
            imageCapture = imageCapture,
            recordingController = recordingController,
            mainExecutor = mainExecutor,
        ),
    )
}
