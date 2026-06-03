package com.powerchina.zhixun.dashcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.powerchina.zhixun.physicalkey.PhotoKeyLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** MCP 拍照用的后台 CameraX 绑定；按键时 preWarm，会话结束或执法仪页释放。 */
object McpCameraHolder {

    private const val TAG = PhotoKeyLog.TAG
    private const val CAPTURE_DELAY_MS = 50L
    private const val CAPTURE_READY_RETRY_MS = 120L
    private const val CAPTURE_TIMEOUT_MS = 12_000L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val capturing = AtomicBoolean(false)

    @Volatile
    private var preWarmHeld = false

    @Volatile
    private var provider: ProcessCameraProvider? = null

    @Volatile
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var bound = false

    @Volatile
    private var binding = false

    @Volatile
    private var captureGeneration = 0

    @Volatile
    private var captureTimeoutToken = 0

    fun ensureAcquired(context: Context) {
        preWarmHeld = true
        acquire(context.applicationContext)
    }

    fun releasePreWarm() {
        preWarmHeld = false
        if (!capturing.get()) {
            release()
        }
    }

    /** 会话结束/超时：强制释放，避免 takePicture 挂死占锁导致后续无法拍照 */
    fun forceReset() {
        mainHandler.post { forceResetLocked("forceReset") }
    }

    fun capture(context: Context, onResult: (Result<File>) -> Unit) {
        val appContext = context.applicationContext
        if (!hasCameraPermission(appContext)) {
            onResult(Result.failure(SecurityException("需要相机权限，请在对话页允许相机访问")))
            return
        }
        if (!capturing.compareAndSet(false, true)) {
            onResult(Result.failure(IllegalStateException("拍照进行中，请稍候")))
            return
        }
        val generation = ++captureGeneration
        val finished = AtomicBoolean(false)
        val finish: (Result<File>) -> Unit = finish@{ result ->
            if (!finished.compareAndSet(false, true)) return@finish
            cancelCaptureTimeout()
            capturing.set(false)
            onResult(result)
            result.onSuccess {
                if (preWarmHeld && generation == captureGeneration) {
                    rebindForNextCapture(appContext)
                }
            }
            if (!preWarmHeld) {
                release()
            }
        }
        captureWithReadyCamera(appContext, generation, finish, retryCount = 0)
    }

    fun pauseForDashcam() {
        preWarmHeld = false
        forceResetLocked("pauseForDashcam")
    }

    private fun captureWithReadyCamera(
        appContext: Context,
        generation: Int,
        finish: (Result<File>) -> Unit,
        retryCount: Int,
    ) {
        acquire(appContext) {
            if (generation != captureGeneration) {
                finish(Result.failure(IllegalStateException("拍照已取消")))
                return@acquire
            }
            val capture = synchronized(lock) { imageCapture }
            if (capture == null) {
                if (retryCount < 2) {
                    mainHandler.postDelayed({
                        captureWithReadyCamera(appContext, generation, finish, retryCount + 1)
                    }, CAPTURE_READY_RETRY_MS)
                } else {
                    finish(Result.failure(IllegalStateException("相机未就绪")))
                }
                return@acquire
            }
            val mainExecutor = ContextCompat.getMainExecutor(appContext)
            val file = DashcamRecordingStore.createPhotoFile(appContext)
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            val timeoutToken = scheduleCaptureTimeout(generation, finish)
            mainHandler.postDelayed({
                if (generation != captureGeneration) return@postDelayed
                capture.takePicture(
                    options,
                    mainExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            if (generation != captureGeneration) return
                            if (timeoutToken != captureTimeoutToken) return
                            cancelCaptureTimeout()
                            Log.i(TAG, "拍照成功 ${file.name}")
                            finish(Result.success(file))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (generation != captureGeneration) return
                            cancelCaptureTimeout()
                            Log.e(TAG, "拍照失败", exception)
                            resetBindingLocked()
                            finish(Result.failure(exception))
                            if (preWarmHeld) {
                                acquire(appContext)
                            }
                        }
                    },
                )
            }, CAPTURE_DELAY_MS)
        }
    }

    private fun scheduleCaptureTimeout(
        generation: Int,
        finish: (Result<File>) -> Unit,
    ): Int {
        val token = ++captureTimeoutToken
        mainHandler.postDelayed({
            if (token != captureTimeoutToken) return@postDelayed
            if (generation != captureGeneration) return@postDelayed
            if (!capturing.get()) return@postDelayed
            Log.e(TAG, "takePicture 超时 ${CAPTURE_TIMEOUT_MS}ms，强制重置相机")
            forceResetLocked("capture_timeout")
            finish(Result.failure(IllegalStateException("拍照超时，请重试")))
        }, CAPTURE_TIMEOUT_MS)
        return token
    }

    private fun cancelCaptureTimeout() {
        captureTimeoutToken++
    }

    private fun rebindForNextCapture(context: Context) {
        if (!preWarmHeld || capturing.get()) return
        resetBindingLocked()
        acquire(context)
    }

    private fun acquire(context: Context, onReady: (() -> Unit)? = null) {
        if (!preWarmHeld && onReady == null) return
        if (!hasCameraPermission(context)) {
            onReady?.invoke()
            return
        }
        synchronized(lock) {
            if (bound && imageCapture != null) {
                onReady?.invoke()
                return
            }
            if (binding) {
                if (onReady != null) {
                    mainHandler.postDelayed({ acquire(context, onReady) }, 80L)
                }
                return
            }
            binding = true
        }
        val mainExecutor = ContextCompat.getMainExecutor(context)
        ProcessCameraProvider.getInstance(context).addListener(
            {
                var ready = false
                synchronized(lock) {
                    binding = false
                    if (bound && imageCapture != null) {
                        ready = true
                    } else {
                        try {
                            if (SharedCameraCapture.dashcamSession != null) {
                                Log.d(TAG, "执法仪占用相机，跳过 MCP 绑定")
                            } else {
                                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                                val capture = ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                    .build()
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    ProcessLifecycleOwner.get(),
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    capture,
                                )
                                provider = cameraProvider
                                imageCapture = capture
                                bound = true
                                ready = true
                                Log.d(TAG, "MCP 相机已绑定")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "MCP 相机绑定失败", e)
                            resetBindingLocked()
                        }
                    }
                }
                if (ready) {
                    onReady?.invoke()
                } else if (onReady != null) {
                    mainHandler.postDelayed({ acquire(context, onReady) }, CAPTURE_READY_RETRY_MS)
                }
            },
            mainExecutor,
        )
    }

    private fun release() {
        synchronized(lock) {
            if (!bound) return
            resetBindingLocked()
            Log.d(TAG, "MCP 相机已释放")
        }
    }

    private fun forceResetLocked(reason: String) {
        captureGeneration++
        cancelCaptureTimeout()
        capturing.set(false)
        synchronized(lock) {
            resetBindingLocked()
        }
        Log.w(TAG, "相机强制重置 reason=$reason")
    }

    private fun resetBindingLocked() {
        try {
            provider?.unbindAll()
        } catch (_: Exception) {
        }
        provider = null
        imageCapture = null
        bound = false
        binding = false
    }

    private fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
