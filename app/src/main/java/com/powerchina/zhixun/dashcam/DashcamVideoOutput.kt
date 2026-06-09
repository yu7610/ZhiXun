package com.powerchina.zhixun.dashcam

import android.content.ContentValues
import android.net.Uri

/** 开录前由 CameraX 写入 MediaStore 的元数据（勿预先 insert item URI） */
data class DashcamVideoOutputRequest(
    val displayName: String,
    val contentValues: ContentValues,
)

/** 录像结束后得到的 MediaStore 条目 */
data class DashcamVideoOutput(
    val uri: Uri,
    val displayName: String,
)
