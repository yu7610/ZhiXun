package com.powerchina.zhixun.dashcam

import android.content.Context
import android.util.Log
import android.view.View
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "DashcamCamera"
private const val BIND_SETTLE_MS = 350L
private const val MAX_BIND_ATTEMPTS = 6

@Composable
fun DashcamCameraPreview(
    lensFacing: Int,
    modifier: Modifier = Modifier,
    rebindToken: Int = 0,
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
    var wasPaused by remember { mutableStateOf(false) }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPaused = true
                    runCatching { cameraProviderRef?.unbindAll() }
                    onSessionReady(null)
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (wasPaused) {
                        wasPaused = false
                        bindGeneration++
                    }
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

    LaunchedEffect(lensFacing, bindGeneration, rebindToken) {
        onSessionReady(null)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.w(TAG, "生命周期未 STARTED，跳过绑定 gen=$bindGeneration")
            return@LaunchedEffect
        }
        McpCameraHolder.pauseForDashcam()
        delay(BIND_SETTLE_MS)
        if (!isActive) return@LaunchedEffect

        previewView.awaitAttachedAndLaidOut()
        if (!isActive) return@LaunchedEffect

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

        var bound = false
        for (attempt in 1..MAX_BIND_ATTEMPTS) {
            if (!isActive) return@LaunchedEffect
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                Log.w(TAG, "绑定前生命周期已暂停 attempt=$attempt")
                break
            }
            try {
                suspendCancellableCoroutine { cont ->
                    previewView.post {
                        if (!cont.isActive) return@post
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
                            cont.resume(Unit)
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }
                bound = true
                Log.i(TAG, "相机绑定成功 gen=$bindGeneration attempt=$attempt")
                break
            } catch (e: Exception) {
                Log.w(TAG, "绑定相机失败 gen=$bindGeneration attempt=$attempt/$MAX_BIND_ATTEMPTS", e)
                onSessionReady(null)
                if (attempt < MAX_BIND_ATTEMPTS) {
                    delay(350L * attempt)
                    McpCameraHolder.pauseForDashcam()
                    delay(BIND_SETTLE_MS)
                    previewView.awaitAttachedAndLaidOut()
                }
            }
        }
        if (!bound) {
            Log.e(TAG, "绑定相机最终失败 gen=$bindGeneration")
            onSessionReady(null)
        }
    }
}

private suspend fun PreviewView.awaitAttachedAndLaidOut() {
    suspendCancellableCoroutine { cont ->
        fun tryResume() {
            if (cont.isActive && isAttachedToWindow) {
                cont.resume(Unit)
            }
        }
        if (isAttachedToWindow) {
            post { tryResume() }
            return@suspendCancellableCoroutine
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                removeOnAttachStateChangeListener(this)
                v.post { tryResume() }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        addOnAttachStateChangeListener(listener)
        cont.invokeOnCancellation { removeOnAttachStateChangeListener(listener) }
    }
}

private fun bindCamera(
    context: Context,
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    lensFacing: Int,
    mainExecutor: Executor,
    onSessionReady: (DashcamCameraSession?) -> Unit,
) {
    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        throw IllegalStateException("生命周期未 STARTED")
    }
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
