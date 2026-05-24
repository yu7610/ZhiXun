package com.powerchina.zhixun.screenrecord

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenRecordingStore {
    private const val SUB_DIR = "screenrecord"

    fun recordingsDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        val dir = File(base, SUB_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun createOutputFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(context), "SCR_$stamp.mp4")
    }
}
