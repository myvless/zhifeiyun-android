package com.github.kr328.clash.zhifeiyun

import android.content.Context
import com.github.kr328.clash.core.model.Proxy
import java.text.Collator
import java.util.Locale

/**
 * Node picker ordering (延迟 / 名称), remembered across launches.
 * Groups (♻️ 自动选择 etc.) and built-ins (DIRECT/REJECT) stay pinned at the top in their
 * original order whatever the sort.
 */
object NodeSort {
    enum class Mode { Delay, Name }

    private const val PREFS = "zhifeiyun_settings"
    private const val KEY = "node_sort"
    private val BUILTIN = setOf("Direct", "Reject", "RejectDrop", "Pass", "Compatible")

    fun load(c: Context): Mode =
        if (c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) == Mode.Name.name) Mode.Name else Mode.Delay

    fun save(c: Context, m: Mode) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, m.name).apply()
    }

    private fun pinned(p: Proxy) = p.isGroup || p.type in BUILTIN

    private fun hasDelay(p: Proxy) = p.delay in 1..60000

    fun apply(list: List<Proxy>, mode: Mode): List<Proxy> {
        val (pin, nodes) = list.partition { pinned(it) }
        val byName = NaturalComparator
        val sorted = when (mode) {
            Mode.Name -> nodes.sortedWith(compareBy(byName) { it.title.ifBlank { it.name } })
            Mode.Delay -> nodes.sortedWith(
                compareBy<Proxy> { if (hasDelay(it)) 0 else 1 }
                    .thenBy { if (hasDelay(it)) it.delay else 0 }
                    .thenBy(byName) { it.title.ifBlank { it.name } }
            )
        }
        return pin + sorted
    }

    /** Natural order: digit runs compare numerically ("节点2" < "节点10"), text via a zh collator. */
    object NaturalComparator : Comparator<String> {
        private val collator = Collator.getInstance(Locale.CHINA)
        private val chunk = Regex("\\d+|\\D+")

        override fun compare(a: String, b: String): Int {
            val x = chunk.findAll(a).map { it.value }.toList()
            val y = chunk.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]; val q = y[i]
                val r = if (p[0].isDigit() && q[0].isDigit()) {
                    val np = p.trimStart('0'); val nq = q.trimStart('0')
                    if (np.length != nq.length) np.length - nq.length else np.compareTo(nq)
                } else collator.compare(p, q)
                if (r != 0) return r
            }
            return x.size - y.size
        }
    }
}
