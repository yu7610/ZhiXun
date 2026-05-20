package com.powerchina.zhixun.dashcam

import java.io.File

data class DashcamClip(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)
