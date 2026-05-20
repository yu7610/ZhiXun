package com.powerchina.zhixun.dashcam

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DashcamRecordingStore {
    private const val SUB_DIR = "dashcam"

    fun fileProviderAuthority(context: Context): String =
        "${context.packageName}.fileprovider"

    fun uriForFile(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, fileProviderAuthority(context), file)

    fun recordingsDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        val dir = File(base, SUB_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun createOutputFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(context), "REC_$stamp.mp4")
    }

    fun createPhotoFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(context), "IMG_$stamp.jpg")
    }

    fun listClips(context: Context): List<DashcamClip> =
        recordingsDir(context)
            .listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                DashcamClip(
                    file = file,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                )
            }
            .orEmpty()
}
