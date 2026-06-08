package com.powerchina.zhixun.dashcam

import java.io.File

data class DashcamPhoto(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)
