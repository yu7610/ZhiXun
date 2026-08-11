package com.powerchina.zhixun.xiaozhi

/**
 * 相机安全检测类型（对齐固件 PushImage / AIAPI_*）。
 *
 * 接口基址：http://8.134.202.195:8001/
 * - 常规 → POST /detect/xiaozhi
 * - 其余 → POST /detect + project_id
 */
enum class VisionCheckKind(
    val toolName: String,
    val description: String,
) {
    NORMAL(
        toolName = "self.camera.take_photo",
        description = "Always remember you have a camera.\n" +
            "Take a photo, detect safety hazards in the image.\n" +
            "If hazards are found, describe the hazards; otherwise, respond that no safety hazards are detected.\n" +
            "Return:\n" +
            "  A JSON object that provides the photo information.",
    ),
    SHATOUJIAO(
        toolName = "self.special_check.shatoujiao",
        description = "Always remember you have a camera.\n" +
            "Take a photo, detect ShaTouJiao special safety hazards in the image.\n" +
            "If hazards are found, describe the hazards; otherwise, respond that no safety hazards are detected.\n" +
            "Return:\n" +
            "  A JSON object that provides the photo information.",
    ),
    ENTRANCE(
        toolName = "self.confined_space.check_entrance",
        description = "Confined space entrance safety check tool.",
    ),
    WEARABLE(
        toolName = "self.confined_space.check_wearable",
        description = "Confined space entrance wearable device safety check tool.",
    ),
    FIRSTAID(
        toolName = "self.confined_space.check_firstaid",
        description = "Confined space entrance first aid device safety check tool.",
    ),
    ;

    companion object {
        fun fromToolName(name: String): VisionCheckKind? =
            entries.firstOrNull { it.toolName == name }
    }
}
