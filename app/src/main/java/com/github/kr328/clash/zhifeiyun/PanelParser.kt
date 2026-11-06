package com.github.kr328.clash.zhifeiyun

/**
 * All SSPanel-UIM HTML / JSON parsing lives in this one file so it can be adjusted
 * if the panel theme changes. Every function is defensive: returns null when unsure.
 *
 * Assumptions (SSPanel-UIM tabler theme, resources/views/tabler/user/index.tpl):
 *  - The universal subscription link is printed on /user, e.g.
 *      <input ... value="https://sub.example.com/sub/TOKEN" id="universal-sub-link">
 *      data-clipboard-text="https://sub.example.com/sub/TOKEN/clash"
 *      universalSubUrl: "https://sub.example.com/sub/TOKEN",
 *    Clash format = <universal>/clash  (route: /sub/{token}/{subtype}).
 *  - Remaining traffic text: "剩余流量 12.3GB"
 *  - Expiry text: "会在 N 天后到期（2026-12-31 23:59:59）"
 */
object PanelParser {
    private val subPatterns = listOf(
        Regex("""id=["']universal-sub-link["'][^>]*?value=["']([^"']+)["']"""),
        Regex("""value=["']([^"']+)["'][^>]*?id=["']universal-sub-link["']"""),
        Regex("""universalSubUrl\s*[:=]\s*["']([^"']+)["']"""),
        Regex("""(https?://[^\s"'<>]+?/sub/[A-Za-z0-9_\-]+)(?:/[a-z0-9]+)?["'<\s]"""),
        // Legacy SSPanel style links: /link/TOKEN?clash=1
        Regex("""(https?://[^\s"'<>]+?/link/[A-Za-z0-9_\-]+\?clash=1)"""),
    )

    /** Returns a Clash subscription URL, or null. */
    fun parseClashSubUrl(html: String): String? {
        for (p in subPatterns) {
            val raw = p.find(html)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val url = raw.replace("&amp;", "&")
            if (!url.startsWith("http")) continue
            return toClashUrl(url)
        }
        return null
    }

    fun toClashUrl(url: String): String {
        if (url.contains("/link/")) return url
        val m = Regex("""^(https?://.+?/sub/[A-Za-z0-9_\-]+)""").find(url)
        val base = m?.groupValues?.get(1) ?: url.trimEnd('/')
        return "$base/clash"
    }

    /** "剩余流量 12.34GB" -> "12.34GB" */
    fun parseRemainingTraffic(html: String): String? {
        val text = stripTags(html)
        return Regex("""剩余流量\s*[:：]?\s*([0-9.,]+\s*[KMGTPE]?i?B)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.replace(" ", "")
    }

    /** Returns expiry text, e.g. "2026-12-31 23:59:59". */
    fun parseExpire(html: String): String? {
        val text = stripTags(html)
        Regex("""到期\s*[（(]\s*(\d{4}-\d{2}-\d{2}(?:\s+\d{2}:\d{2}(?::\d{2})?)?)""")
            .find(text)?.let { return it.groupValues[1] }
        Regex("""(?:到期时间|过期时间|等级过期|会员到期|有效期至)\s*[:：]?\s*(\d{4}-\d{2}-\d{2}(?:\s+\d{2}:\d{2}(?::\d{2})?)?)""")
            .find(text)?.let { return it.groupValues[1] }
        return null
    }

    /** Parse UIM JSON reply {"ret":0,"msg":"..."}; returns Pair(ret, msg) or null. */
    fun parseRetMsg(body: String): Pair<Int, String>? {
        val ret = Regex(""""ret"\s*:\s*(-?\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val msgRaw = Regex(""""msg"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)?.groupValues?.get(1) ?: ""
        return ret to unescapeJson(msgRaw)
    }

    fun isLoginPage(html: String): Boolean =
        html.contains("/auth/login") && html.contains("id=\"password\"")

    private fun stripTags(html: String): String =
        html.replace(Regex("(?s)<script.*?</script>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> if (i + 5 < s.length) {
                        s.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 6; continue
                    }
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(n)
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
