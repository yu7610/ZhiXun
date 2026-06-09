package com.powerchina.zhixun.dashcam

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 将录像导出到外置 TF 卡 DCIM/100MEDIA/MP4，使用执法仪系统命名（yyyyMMddHHmmss-00N.MP4），
 * 供设备自带「视频」页展示。
 */
object DashcamGalleryExporter {

    const val TAG = "DashcamGallery"

    fun exportToGallery(context: Context, videoFile: File): Result<File> = runCatching {
        require(videoFile.exists() && videoFile.length() > 0L) { "视频文件不存在或为空" }
        val mp4Dir = DashcamRecordingStore.resolveExternalTfMp4Dir(context)
            ?: throw IllegalStateException("未找到外置 TF 卡 DCIM/100MEDIA/MP4")
        val stem = DashcamRecordingStore.toOemMp4Stem(videoFile)
        val dest = DashcamRecordingStore.resolveOemMp4Dest(mp4Dir, videoFile)
        val oemName = dest.name
        val needsCopy = !dest.exists() || dest.length() != videoFile.length()
        if (needsCopy) {
            copyToOemMp4ViaShell(videoFile, dest)
        } else {
            Log.d(TAG, "OEM MP4 已存在，刷新索引: ${dest.absolutePath}")
        }
        val mapFile = writeOemMapViaShell(context, dest, stem)
        notifyOemGalleryRefresh(context, dest, mapFile)
        Log.i(
            TAG,
            "已导出系统视频: ${videoFile.name} -> $oemName path=${dest.absolutePath} " +
                "size=${dest.length()} map=${mapFile.name}",
        )
        dest
    }.onFailure { e ->
        Log.e(TAG, "导出系统视频失败 ${videoFile.name}", e)
    }

    /**
     * TF 卡路径应用直写会 EPERM，仅通过 shell cp 写入（与 adb shell cp 相同机制）。
     */
    private fun copyToOemMp4ViaShell(source: File, dest: File) {
        if (!shellCopy(source, dest)) {
            throw IllegalStateException("shell cp 写入 TF 卡失败: ${dest.absolutePath}")
        }
        runCatching { Runtime.getRuntime().exec(arrayOf("sync")).waitFor() }
        Log.i(TAG, "shell cp -> OEM MP4: ${dest.name} size=${dest.length()}")
    }

    private fun writeOemMapViaShell(context: Context, mp4Dest: File, stem: String): File {
        val mapFile = DashcamRecordingStore.oemMapFileFor(mp4Dest)
        val temp = File(context.cacheDir, "oem_${stem}.map")
        temp.writeText(DashcamRecordingStore.formatOemMapContent(stem))
        if (!shellCopy(temp, mapFile)) {
            temp.delete()
            throw IllegalStateException("shell cp 写入 MAP 失败: ${mapFile.absolutePath}")
        }
        temp.delete()
        Log.i(TAG, "已写入 MAP 侧车: ${mapFile.name}")
        return mapFile
    }

    private fun shellCopy(source: File, dest: File): Boolean {
        val destDir = dest.parentFile ?: return false
        return runCatching {
            if (!destDir.exists()) {
                Runtime.getRuntime().exec(arrayOf("mkdir", "-p", destDir.absolutePath)).waitFor()
            }
            if (dest.exists()) {
                Runtime.getRuntime().exec(arrayOf("rm", "-f", dest.absolutePath)).waitFor()
            }
            val process = Runtime.getRuntime().exec(
                arrayOf("cp", source.absolutePath, dest.absolutePath),
            )
            process.waitFor() == 0 &&
                dest.exists() &&
                dest.length() == source.length()
        }.onFailure { err ->
            Log.w(TAG, "shell cp 失败 ${source.absolutePath} -> ${dest.absolutePath}", err)
        }.getOrDefault(false)
    }

    private fun notifyOemGalleryRefresh(context: Context, mp4File: File, mapFile: File) {
        val app = context.applicationContext
        MediaScannerConnection.scanFile(
            app,
            arrayOf(mp4File.absolutePath, mapFile.absolutePath),
            arrayOf("video/mp4", "application/octet-stream"),
        ) { path, uri -> Log.i(TAG, "MediaScanner path=$path uri=$uri") }

        @Suppress("DEPRECATION")
        app.sendBroadcast(
            Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(mp4File)),
        )

        runCatching {
            Runtime.getRuntime().exec(
                arrayOf(
                    "am", "broadcast",
                    "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                    "-d", "file://${mp4File.absolutePath}",
                ),
            ).waitFor()
        }.onFailure { err -> Log.w(TAG, "shell am broadcast 失败", err) }

        app.contentResolver.notifyChange(Uri.fromFile(mp4File), null)
        Log.i(TAG, "已通知系统刷新 OEM 视频索引: ${mp4File.name}")
    }
}
