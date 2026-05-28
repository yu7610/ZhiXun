package com.powerchina.zhixun.xiaozhi

import android.app.Application
import android.content.Intent
import android.util.Log
import com.powerchina.zhixun.MainActivity
import com.powerchina.zhixun.dashcam.SharedCameraCapture
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 应用级拍照上传：不依赖聊天页生命周期，物理键/执法仪页均可触发。
 */
object XiaozhiPhotoCoordinator {

    private const val TAG = "XiaozhiPhoto"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var uploadInProgress = false

    fun register(application: Application) {
        scope.launch {
            XiaozhiAppEvents.photoCaptureRequests.collect {
                Log.i(TAG, "收到拍照请求")
                runUpload(application) {
                    val file = capturePhoto(application)
                    if (file == null) {
                        XiaozhiAppEvents.emitPhotoResult(
                            PhotoResult(
                                file = null,
                                uploadResult = Result.failure(IllegalStateException("拍照失败")),
                            ),
                        )
                        return@runUpload
                    }
                    uploadAndNotify(application, file)
                }
            }
        }
        scope.launch {
            XiaozhiAppEvents.photoShareRequests.collect { file ->
                Log.i(TAG, "收到分享照片 ${file.name}")
                runUpload(application) {
                    uploadAndNotify(application, file)
                }
            }
        }
        Log.i(TAG, "已注册拍照上传协调器")
    }

    private fun runUpload(application: Application, block: suspend () -> Unit) {
        if (uploadInProgress) {
            Log.d(TAG, "上传进行中，跳过")
            return
        }
        uploadInProgress = true
        XiaozhiAppEvents.beginPhotoSession()
        XiaozhiWakeForegroundService.pauseListening(application)
        bringConversationToFront(application)
        scope.launch {
            try {
                block()
            } finally {
                uploadInProgress = false
            }
        }
    }

    private fun bringConversationToFront(application: Application) {
        if (XiaozhiAppEvents.conversationScreenVisible) {
            Log.d(TAG, "已在对话页前台，跳过 Activity 拉起")
            return
        }
        val intent = Intent(application, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_XIAOZHI, true)
            putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
        }
        application.startActivity(intent)
    }

    private suspend fun uploadAndNotify(application: Application, photoFile: File) {
        if (!photoFile.exists()) {
            XiaozhiAppEvents.emitPhotoResult(
                PhotoResult(
                    file = photoFile,
                    uploadResult = Result.failure(IllegalStateException("照片文件不存在")),
                ),
            )
            return
        }
        XiaozhiAppEvents.emitPhotoResult(
            PhotoResult(
                file = photoFile,
                uploadResult = Result.success(Unit),
                captureOnly = true,
            ),
        )
        val uploadResult = XiaozhiPhotoUploader.uploadPhoto(
            application = application,
            photoFile = photoFile,
            prompt = "请描述这张照片",
        )
        XiaozhiAppEvents.emitPhotoResult(
            PhotoResult(
                file = photoFile,
                uploadResult = uploadResult.map { },
                visionDescription = uploadResult.getOrNull(),
            ),
        )
    }

    private suspend fun capturePhoto(application: Application): File? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                SharedCameraCapture.capture(application) { result ->
                    if (cont.isActive) {
                        cont.resume(result.getOrNull())
                    }
                }
            }
        }
}
