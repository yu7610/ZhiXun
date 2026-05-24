package com.powerchina.zhixun.xiaozhi.wake

/**
 * 唤醒词匹配：你好，智询（普通话）
 */
object WakePhraseMatcher {

    const val WAKE_PHRASE = "你好，智询"

    private const val CORE = "你好智询"

    /** STT 常把「智询」误识为同音字 */
    private val HOMOPHONE_TAILS = setOf(
        "智询", "智讯", "智寻", "知询", "之询", "这询", "资讯", "只询", "制询",
    )

    fun matches(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false

        if (normalized == CORE) return true

        if (normalized.matches(Regex("^${CORE}[啊吗呀呢]?$"))) return true

        // 仅「你好」短唤醒
        if (normalized == "你好" || normalized.matches(Regex("^你好[啊吗呀呢]?$"))) return true

        if (normalized.startsWith(CORE) && normalized.length <= CORE.length + 2) return true

        // 「你好」+ 同音误识尾词，如：你好这询 / 你好资讯
        if (normalized.startsWith("你好") && normalized.length in 4..8) {
            val tail = normalized.removePrefix("你好").trimEnd('啊', '吗', '呀', '呢')
            if (tail.isEmpty()) return true
            if (HOMOPHONE_TAILS.any { tail == it || tail.contains(it) }) return true
            if (tail.endsWith("询") || tail.endsWith("讯")) return true
        }

        return false
    }

    /** 结束对话语句（如「退下」），服务器通常会随后断开连接 */
    fun isSessionEndPhrase(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        if (SESSION_END_KEYWORDS.any { normalized.contains(it) }) return true
        // STT 同音误识：特下、退休
        if (normalized.matches(Regex("^[退特]下[啊吗呀呢]?$"))) return true
        if (normalized.contains("退休")) return true
        return false
    }

    private val SESSION_END_KEYWORDS = listOf(
        "退下",
        "退出",
        "结束对话",
        "结束吧",
        "别说了",
        "停止对话",
    )

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
