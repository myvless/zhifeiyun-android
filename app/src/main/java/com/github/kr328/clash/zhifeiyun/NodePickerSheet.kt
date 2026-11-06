package com.github.kr328.clash.zhifeiyun

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import com.github.kr328.clash.R
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.zhifeiyun.Ui.dp
import com.github.kr328.clash.zhifeiyun.Ui.primaryButton
import com.github.kr328.clash.zhifeiyun.Ui.text
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Light bottom sheet for picking a node: name on the left (wraps), latency in a fixed
 * right-aligned column, radio indicator for the selected node, 测速 / 取消 at the bottom.
 */
class NodePickerSheet(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSelect: (String) -> Unit,
    /** Runs a health check and returns the refreshed proxy list (null on failure). */
    private val onSpeedTest: suspend () -> List<Proxy>?,
) {
    private val c = context
    private val delayViews = HashMap<String, TextView>()
    private var tested = false
    private var sort = NodeSort.load(context)
    private var current: List<Proxy> = emptyList()

    fun show(proxies: List<Proxy>, now: String) {
        current = proxies
        val dialog = BottomSheetDialog(c)

        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                val r = c.dp(20).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        // Drag handle
        root.addView(View(c).apply {
            background = Ui.rounded(0xFFD5DCE5.toInt(), c.dp(2).toFloat())
        }, LinearLayout.LayoutParams(c.dp(36), c.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = c.dp(10)
        })

        // Header
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(20), c.dp(12), c.dp(20), c.dp(10))
        }
        header.addView(c.text(c.getString(R.string.zfy_choose_node), 17f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(c.text(c.getString(R.string.zfy_node_count, proxies.size), 12f, Ui.TEXT_SUB))
        val sortToggle = sortToggle()
        header.addView(sortToggle.root, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = c.dp(10) })
        root.addView(header)
        root.addView(divider())

        // List
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val render: () -> View? = {
            list.removeAllViews()
            delayViews.clear()
            var sel: View? = null
            NodeSort.apply(current, sort).forEach { p ->
                val row = row(p, p.name == now) {
                    dialog.dismiss()
                    if (p.name != now) onSelect(p.name)
                }
                if (p.name == now) sel = row
                list.addView(row)
            }
            sel
        }
        val selectedRow: View? = render()
        val scroll = NestedScrollView(c).apply {
            isFillViewport = false
            addView(list)
        }
        val screenH = c.resources.displayMetrics.heightPixels
        val listH = if (proxies.size > 7) (screenH * 0.62f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH))
        sortToggle.onChange = { mode ->
            if (mode != sort) {
                sort = mode
                NodeSort.save(c, mode)
                sortToggle.bind(mode)
                render()
                scroll.scrollTo(0, 0)
            }
        }
        root.addView(divider())

        // Actions
        val actions = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(c.dp(20), c.dp(12), c.dp(20), c.dp(16))
        }
        val cancel = c.text(c.getString(android.R.string.cancel), 15f, Ui.TEXT_SUB, true).apply {
            gravity = Gravity.CENTER
            background = Ui.rounded(0xFFEFF2F6.toInt(), c.dp(22).toFloat())
            setOnClickListener { dialog.dismiss() }
        }
        val test = c.primaryButton(c.getString(R.string.zfy_speed_test))
        test.setOnClickListener {
            test.isEnabled = false
            test.text = c.getString(R.string.zfy_testing)
            scope.launch {
                val updated = runCatching { onSpeedTest() }.getOrNull()
                tested = true
                if (updated != null && updated.isNotEmpty()) {
                    current = updated
                    render() // re-sort with the fresh latencies
                    scroll.scrollTo(0, 0)
                } else {
                    delayViews.values.forEach { bindDelay(it, -1) }
                }
                test.isEnabled = true
                test.text = c.getString(R.string.zfy_speed_test)
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, c.dp(44), 1f).apply { rightMargin = c.dp(6) })
        actions.addView(test, LinearLayout.LayoutParams(0, c.dp(44), 1f).apply { leftMargin = c.dp(6) })
        root.addView(actions)

        val basePad = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePad + insets.systemWindowInsetBottom)
            insets
        }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
            selectedRow?.let { r -> scroll.post { scroll.scrollTo(0, (r.top - c.dp(96)).coerceAtLeast(0)) } }
        }
        dialog.show()
    }

    private class SortToggle(val root: LinearLayout, val delay: TextView, val name: TextView) {
        var onChange: (NodeSort.Mode) -> Unit = {}
        fun bind(mode: NodeSort.Mode) {
            listOf(delay to NodeSort.Mode.Delay, name to NodeSort.Mode.Name).forEach { (v, m) ->
                val on = m == mode
                v.setTextColor(if (on) Color.WHITE else Ui.TEXT_SUB)
                v.background = if (on) Ui.rounded(Ui.BLUE, v.resources.displayMetrics.density * 12) else null
            }
        }
    }

    /** Small segmented "延迟 | 名称" control. */
    private fun sortToggle(): SortToggle {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(c.dp(2), c.dp(2), c.dp(2), c.dp(2))
            background = Ui.rounded(0xFFEFF2F6.toInt(), c.dp(14).toFloat())
        }
        fun seg(label: String) = c.text(label, 12f, Ui.TEXT_SUB, true).apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(10), c.dp(3), c.dp(10), c.dp(3))
        }
        val d = seg(c.getString(R.string.zfy_sort_delay))
        val n = seg(c.getString(R.string.zfy_sort_name))
        box.addView(d); box.addView(n)
        val t = SortToggle(box, d, n)
        d.setOnClickListener { t.onChange(NodeSort.Mode.Delay) }
        n.setOnClickListener { t.onChange(NodeSort.Mode.Name) }
        t.bind(sort)
        return t
    }

    private fun divider() = View(c).apply { setBackgroundColor(0xFFEDF1F6.toInt()) }
        .also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1) }

    private fun row(p: Proxy, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(20), c.dp(12), c.dp(20), c.dp(12))
            minimumHeight = c.dp(48)
            if (selected) setBackgroundColor(0xFFEEF5FE.toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(View(c).apply { background = radio(selected) },
            LinearLayout.LayoutParams(c.dp(18), c.dp(18)))
        val name = c.text(p.title.ifBlank { p.name }, 15f, if (selected) Ui.BLUE_DARK else Ui.TEXT, selected).apply {
            setLineSpacing(0f, 1.1f)
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = c.dp(12); rightMargin = c.dp(8)
        })
        val delay = c.text("", 13f, Ui.TEXT_SUB, true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        bindDelay(delay, p.delay)
        delayViews[p.name] = delay
        row.addView(delay, LinearLayout.LayoutParams(c.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun bindDelay(v: TextView, delay: Int) {
        if (delay in 1..60000) {
            v.text = c.getString(R.string.zfy_delay_ms, delay)
            v.setTextColor(
                when {
                    delay <= 300 -> 0xFF22B573.toInt()
                    delay <= 800 -> 0xFFF59E0B.toInt()
                    else -> 0xFFEF4444.toInt()
                }
            )
        } else if (tested) {
            v.text = c.getString(R.string.zfy_delay_timeout)
            v.setTextColor(0xFFEF4444.toInt())
        } else {
            v.text = "-"
            v.setTextColor(0xFFB0B8C4.toInt())
        }
    }

    private fun radio(selected: Boolean): android.graphics.drawable.Drawable {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(c.dp(2), if (selected) Ui.BLUE else 0xFFC3CBD6.toInt())
        }
        if (!selected) return ring
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Ui.BLUE)
        }
        return LayerDrawable(arrayOf(ring, dot)).apply {
            val inset = c.dp(4)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }
}
