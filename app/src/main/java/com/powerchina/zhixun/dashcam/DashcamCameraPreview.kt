package com.powerchina.zhixun.dashcam

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
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
import com.pedro.library.view.OpenGlView
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "DashcamCamera"
private const val BIND_SETTLE_MS = 350L
private const val MAX_BIND_ATTEMPTS = 6

/**
 * 执法仪预览：RootEncoder OpenGlView，一路相机同时支持录像与 RTSP 推流。
 */
@Composable
fun DashcamCameraPreview(
    lensFacing: Int,
    modifier: Modifier = Modifier,
    rebindToken: Int = 0,
    onSessionReady: (DashcamCameraSession?) -> Unit,
) {
    val context = LocalContext.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val openGlView = remember {
        OpenGlView(context).apply {
            keepScreenOn = true
        }
    }
    var engineRef by remember { mutableStateOf<DashcamRtspEngine?>(null) }
    var bindGeneration by remember { mutableIntStateOf(0) }

    AndroidView(
        factory = { openGlView },
        modifier = modifier,
    )

    DisposableEffect(Unit) {
        onDispose {
            onSessionReady(null)
            engineRef?.release()
            engineRef = null
        }
    }

    LaunchedEffect(lensFacing, bindGeneration, rebindToken) {
        onSessionReady(null)
        McpCameraHolder.pauseForDashcam()
        delay(BIND_SETTLE_MS)
        if (!isActive) return@LaunchedEffect

        openGlView.awaitAttachedAndLaidOut()
        if (!isActive) return@LaunchedEffect

        var bound = false
        for (attempt in 1..MAX_BIND_ATTEMPTS) {
            if (!isActive) return@LaunchedEffect
            try {
                suspendCancellableCoroutine { cont ->
                    openGlView.post {
                        if (!cont.isActive) return@post
                        try {
                            val engine = engineRef ?: DashcamRtspEngine(context, openGlView).also {
                                engineRef = it
                            }
                            if (!engine.prepareAndStartPreview(lensFacing)) {
                                throw IllegalStateException("prepare/preview 失败")
                            }
                            val session = DashcamCameraSession(
                                context = context,
                                engine = engine,
                                recordingController = DashcamRecordingController(context, engine, mainExecutor),
                                mainExecutor = mainExecutor,
                            )
                            onSessionReady(session)
                            cont.resume(Unit)
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }
                bound = true
                Log.i(TAG, "RootEncoder 预览就绪 gen=$bindGeneration attempt=$attempt facing=$lensFacing")
                break
            } catch (e: Exception) {
                Log.w(TAG, "预览启动失败 attempt=$attempt/$MAX_BIND_ATTEMPTS", e)
                onSessionReady(null)
                if (attempt < MAX_BIND_ATTEMPTS) {
                    delay(350L * attempt)
                    McpCameraHolder.pauseForDashcam()
                    delay(BIND_SETTLE_MS)
                    // 彻底重建 engine
                    engineRef?.release()
                    engineRef = null
                    openGlView.awaitAttachedAndLaidOut()
                }
            }
        }
        if (!bound) {
            Log.e(TAG, "预览最终失败 gen=$bindGeneration")
            onSessionReady(null)
        }
    }
}

private suspend fun View.awaitAttachedAndLaidOut() {
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
