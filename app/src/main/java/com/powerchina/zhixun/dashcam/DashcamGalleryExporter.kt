package com.powerchina.zhixun.dashcam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 将执法仪录像写入系统相册（DCIM/ZhiXun），图库/文件管理器可见。
 */
object DashcamGalleryExporter {

    const val TAG = "DashcamGallery"
    private val ALBUM_DIR = "${Environment.DIRECTORY_DCIM}/ZhiXun"

    fun exportToGallery(context: Context, videoFile: File): Result<Uri> = runCatching {
        require(videoFile.exists() && videoFile.length() > 0L) { "视频文件不存在或为空" }
        val resolver = context.applicationContext.contentResolver
        val pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, ALBUM_DIR)
            if (pending) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法创建相册条目")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                videoFile.inputStream().buffered().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("无法写入相册")
            if (pending) {
                val published = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, published, null, null)
            }
            Log.i(TAG, "已导出相册 $ALBUM_DIR/${videoFile.name} uri=$uri")
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }.onFailure { e ->
        Log.e(TAG, "导出相册失败 ${videoFile.name}", e)
    }
}
