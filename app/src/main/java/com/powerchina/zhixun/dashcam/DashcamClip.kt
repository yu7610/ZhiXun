package com.powerchina.zhixun.dashcam

import android.net.Uri
import java.io.File

enum class DashcamClipType {
    VIDEO,
    AUDIO,
}

data class DashcamClip(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val type: DashcamClipType,
    val uri: Uri? = null,
)
