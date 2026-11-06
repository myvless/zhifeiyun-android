package com.github.kr328.clash.zhifeiyun

import android.os.Build
import java.nio.charset.Charset

/**
 * Minimal pinyin helper without third-party libraries.
 *  - API 29+: android.icu.text.Transliterator("Han-Latin") gives full syllables.
 *  - All API levels: GBK code-range lookup gives the initial letter of the ~3755
 *    common (GB2312 level-1) characters, which are ordered by pinyin.
 */
object Pinyin {
    private val gbk: Charset? = runCatching { Charset.forName("GBK") }.getOrNull()

    private val bounds = intArrayOf(
        45217, 45253, 45761, 46318, 46826, 47010, 47297, 47614, 48119, 49062, 49324, 49896,
        50371, 50614, 50622, 50906, 51387, 51446, 52218, 52698, 52980, 53689, 54481
    )
    private const val letters = "abcdefghjklmnopqrstwxyz"
    private const val END = 55290

    private val translit: Any? by lazy {
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
        }.getOrNull() else null
    }

    private val cache = HashMap<Char, String?>()

    fun isHan(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF

    /** Lower-case syllable (API 29+) or single initial letter; null when unknown. */
    @Synchronized
    fun of(ch: Char): String? {
        if (!isHan(ch)) return null
        if (cache.containsKey(ch)) return cache[ch]
        var r: String? = null
        if (Build.VERSION.SDK_INT >= 29) {
            val t = translit as? android.icu.text.Transliterator
            if (t != null) {
                r = runCatching { t.transliterate(ch.toString()) }.getOrNull()
                    ?.filter { it in 'a'..'z' }?.ifEmpty { null }
            }
        }
        if (r == null) r = gbkInitial(ch)?.toString()
        cache[ch] = r
        return r
    }

    private fun gbkInitial(ch: Char): Char? {
        val cs = gbk ?: return null
        val b = runCatching { ch.toString().toByteArray(cs) }.getOrNull() ?: return null
        if (b.size < 2) return null
        val code = (b[0].toInt() and 0xFF) * 256 + (b[1].toInt() and 0xFF)
        if (code < bounds[0] || code >= END) return null
        var i = bounds.size - 1
        while (i > 0 && code < bounds[i]) i--
        return letters[i]
    }

    /** Index letter A–Z for the first character, or '#'. */
    fun indexLetter(label: String): Char {
        val ch = label.trimStart().firstOrNull() ?: return '#'
        if (ch in 'a'..'z' || ch in 'A'..'Z') return ch.uppercaseChar()
        if (ch in 'ａ'..'ｚ' || ch in 'Ａ'..'Ｚ') return (ch - 0xFEE0).uppercaseChar()
        val p = of(ch)?.firstOrNull() ?: return '#'
        return if (p in 'a'..'z') p.uppercaseChar() else '#'
    }

    /** "微信 WeChat" -> initials "wxwechat"-style string of first letters: "wx" + ascii kept. */
    fun initials(label: String): String = buildString {
        for (ch in label) {
            when {
                isHan(ch) -> of(ch)?.firstOrNull()?.let { append(it) }
                ch.isLetterOrDigit() -> append(ch.lowercaseChar())
            }
        }
    }

    /** Full pinyin (or initials where unknown), used for sorting and search. */
    fun full(label: String): String = buildString {
        for (ch in label) {
            when {
                isHan(ch) -> append(of(ch) ?: "")
                ch.isLetterOrDigit() -> append(ch.lowercaseChar())
            }
        }
    }
}
