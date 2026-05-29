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
        // 明确结束指令：任意位置包含即算
        if (SESSION_END_KEYWORDS.any { normalized.contains(it) }) return true
        // 去掉句尾语气词后再做短指令判定
        val core = normalized.trimEnd('啊', '吗', '呀', '呢', '了', '吧', '哦', '喔', '啦')
        if (core.isEmpty()) return false
        // 「退下」整词及其常见同音/近音误识（退吓/腿下/推下/退霞/退休…），仅整句为该短词时匹配
        if (core.length == 2 && core[0] in TUIXIA_HEAD && core[1] in TUIXIA_TAIL) return true
        // 「退下来」「退下去」
        if (core.length == 3 && core[0] in TUIXIA_HEAD && core[1] in TUIXIA_TAIL &&
            (core[2] == '来' || core[2] == '去')
        ) {
            return true
        }
        // 短告别语
        if (core in SHORT_FAREWELLS) return true
        return false
    }

    private val SESSION_END_KEYWORDS = listOf(
        "退下",
        "退出",
        "结束对话",
        "结束吧",
        "别说了",
        "停止对话",
        "退下吧",
    )

    /** 「退下」首字的同音/近音误识（tuì/tuī/tuǐ 等） */
    private val TUIXIA_HEAD = setOf('退', '推', '腿', '褪', '蜕', '颓', '煺', '特')

    /** 「退下」尾字的同音/近音误识（xià，及「退休」的「休」） */
    private val TUIXIA_TAIL = setOf('下', '吓', '夏', '厦', '霞', '罅', '休')

    /** 整句即为这些短词时视为结束对话 */
    private val SHORT_FAREWELLS = setOf("再见", "拜拜", "拜", "不聊了", "没事了", "不用了", "退了", "结束")

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
