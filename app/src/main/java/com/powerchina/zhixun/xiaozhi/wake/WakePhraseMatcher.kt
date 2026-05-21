package com.powerchina.zhixun.xiaozhi.wake

/**
 * 唤醒词匹配：你好
 */
object WakePhraseMatcher {

    const val WAKE_PHRASE = "你好"

    fun matches(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false

        if (normalized == WAKE_PHRASE) return true

        // 短句口语：你好啊 / 你好吗 / 你好呀 / 你好呢
        if (normalized.matches(Regex("^你好[啊吗呀呢]?$"))) return true

        // 容错：STT 带「小智」等尾词，仍以「你好」开头且较短
        if (normalized.startsWith(WAKE_PHRASE) && normalized.length <= 6) return true

        return false
    }

    private fun normalize(text: String): String {
        return text
            .replace("\\s".toRegex(), "")
            .replace("，", "")
            .replace(",", "")
            .replace("。", "")
            .replace("！", "")
            .replace("!", "")
            .replace("？", "")
            .replace("?", "")
            .lowercase()
    }
}
