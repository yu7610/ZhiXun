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

    fun createAudioOutputFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(context), "AUD_$stamp.m4a")
    }

    fun createCompressTempFile(source: File): File =
        File(source.parentFile, "${source.name}.compressing")

    fun createPhotoFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(context), "IMG_$stamp.jpg")
    }

    fun listPhotos(context: Context): List<DashcamPhoto> =
        recordingsDir(context)
            .listFiles { file ->
                file.isFile && (
                    file.extension.equals("jpg", ignoreCase = true) ||
                        file.extension.equals("jpeg", ignoreCase = true)
                    )
            }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                DashcamPhoto(
                    file = file,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                )
            }
            .orEmpty()

    fun listClips(context: Context): List<DashcamClip> =
        recordingsDir(context)
            .listFiles { file ->
                file.isFile && (
                    file.extension.equals("mp4", ignoreCase = true) ||
                        file.extension.equals("m4a", ignoreCase = true)
                    )
            }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val type = if (file.extension.equals("m4a", ignoreCase = true)) {
                    DashcamClipType.AUDIO
                } else {
                    DashcamClipType.VIDEO
                }
                DashcamClip(
                    file = file,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    type = type,
                )
            }
            .orEmpty()
}
