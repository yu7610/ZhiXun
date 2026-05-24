package com.powerchina.zhixun.dashcam

import android.content.Context
import java.io.File

/**
 * 优先复用执法仪页面的相机，否则使用后台快速拍照。
 */
object SharedCameraCapture {

    @Volatile
    var dashcamSession: DashcamCameraSession? = null

    fun capture(context: Context, onResult: (Result<File>) -> Unit) {
        val session = dashcamSession
        if (session != null) {
            val file = DashcamRecordingStore.createPhotoFile(context.applicationContext)
            session.takePicture(file, onResult)
            return
        }
        QuickPhotoCapture.capture(context, onResult)
    }
}
