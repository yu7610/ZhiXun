package com.powerchina.zhixun.dashcam

import android.content.Context
import android.util.Log
import com.powerchina.zhixun.xiaozhi.VisionCheckKind
import com.powerchina.zhixun.xiaozhi.XiaozhiVisionClient

/**
 * 录屏过程中定时上传预览帧到隐患检测接口。
 */
object RecordingFrameUploader {

    const val TAG = "shuoyu"

    fun uploadFrame(
        context: Context,
        deviceId: String,
        jpegBytes: ByteArray,
        filename: String,
    ): Result<String> =
        XiaozhiVisionClient.detectImageFile(
            context = context.applicationContext,
            deviceId = deviceId,
            jpegBytes = jpegBytes,
            filename = filename,
            kind = VisionCheckKind.NORMAL,
        ).map { it.rawJson }
            .onSuccess { raw ->
                Log.i(TAG, "拍照上传接口返回: $raw")
            }
            .onFailure { e ->
                Log.e(TAG, "录屏帧上传失败", e)
            }

    /** 从检测响应提取需播报文案；无隐患或空则返回 null */
    fun parseSpeakText(raw: String): String? =
        XiaozhiVisionClient.speakTextFromDetectRaw(raw)
}
