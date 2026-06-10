package com.powerchina.zhixun.dashcam

import android.content.Context
import android.util.Log
import com.powerchina.zhixun.physicalkey.PhotoKeyLog
import java.io.File

/**
 * 优先复用执法仪页面的相机，否则使用后台快速拍照。
 */
object SharedCameraCapture {

    @Volatile
    var dashcamSession: DashcamCameraSession? = null

    private val captureGate = Any()

    @Volatile
    private var captureInProgress = false

    fun releasePreWarm() {
        QuickPhotoCapture.releasePreWarm()
    }

    fun forceReset() {
        synchronized(captureGate) {
            captureInProgress = false
        }
        QuickPhotoCapture.forceReset()
        Log.w(TAG, "SharedCameraCapture 强制重置")
    }

    fun capture(context: Context, onResult: (Result<File>) -> Unit) {
        synchronized(captureGate) {
            if (captureInProgress) {
                Log.w(TAG, "已有 MCP 拍照进行中")
                onResult(Result.failure(IllegalStateException("拍照进行中，请稍候")))
                return
            }
            captureInProgress = true
        }
        val finish: (Result<File>) -> Unit = { result ->
            captureInProgress = false
            onResult(result)
        }
        val session = dashcamSession.takeIf { DashcamForeground.isActive }
        if (session != null) {
            val file = DashcamRecordingStore.createPhotoFile(context.applicationContext)
            session.takePicture(file, finish)
            return
        }
        QuickPhotoCapture.capture(context) { result ->
            finish(result)
        }
    }

    private const val TAG = PhotoKeyLog.TAG
}
