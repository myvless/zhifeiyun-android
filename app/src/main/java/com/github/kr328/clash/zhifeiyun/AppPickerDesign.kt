package com.github.kr328.clash.zhifeiyun

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.github.kr328.clash.R
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.zhifeiyun.Ui.dp
import com.github.kr328.clash.zhifeiyun.Ui.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

class PickApp(
    val pkg: String,
    val label: String,
    /** Shown by default: has a launcher entry and is not a pure system package. */
    val userApp: Boolean,
    val info: ApplicationInfo,
) {
    val letter: Char = Pinyin.indexLetter(label)
    val sortKey: String = Pinyin.full(label) + "\u0000" + label.lowercase(Locale.ROOT)
    val initials: String = Pinyin.initials(label)
    val full: String = Pinyin.full(label)
    val labelLower: String = label.lowercase(Locale.ROOT)

    fun matches(q: String): Boolean =
        labelLower.contains(q) || pkg.lowercase(Locale.ROOT).contains(q) ||
            initials.contains(q) || full.contains(q)
}

/** Per-app picker for 应用 mode: search, A–Z sections, side index, system-app toggle. */
class AppPickerDesign(
    context: Context,
    private val selected: MutableSet<String>,
    showSystem: Boolean,
) : Design<AppPickerDesign.Request>(context) {
    enum class Request { Close, ToggleSystem }

    private sealed class Row {
        class Header(val letter: Char) : Row()
        class App(val app: PickApp) : Row()
    }

    private val pm: PackageManager = context.packageManager
    private var all: List<PickApp> = emptyList()
    private var rows: List<Row> = emptyList()
    private var query = ""
    var showSystem: Boolean = showSystem
        private set

    private val list: ListView
    private val countText: TextView
    private val emptyText: TextView
    private val progress: ProgressBar
    private val systemBox: CheckBox
    private val indexBar: LinearLayout
    private val iconCache = HashMap<String, Drawable>()
    private val iconLoader = Executors.newFixedThreadPool(2)
    private val adapter = Adapter()

    override val root: View

    init {
        val c = context
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.GREY_BG)
            fitsSystemWindows = true
        }

        // Toolbar: back · title · 完成
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(4), c.dp(6), c.dp(8), c.dp(2))
        }
        bar.addView(c.text("‹", 30f, Ui.BLUE_DARK).apply {
            gravity = Gravity.CENTER
            setOnClickListener { requests.trySend(Request.Close) }
        }, LinearLayout.LayoutParams(c.dp(44), c.dp(44)))
        bar.addView(c.text(c.getString(R.string.zfy_pick_apps_title), 19f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(c.text(c.getString(R.string.zfy_done), 16f, Ui.BLUE, true).apply {
            setPadding(c.dp(12), c.dp(8), c.dp(8), c.dp(8))
            setOnClickListener { requests.trySend(Request.Close) }
        })
        col.addView(bar)

        // Search
        val search = EditText(c).apply {
            hint = c.getString(R.string.zfy_search_apps_hint)
            setSingleLine()
            textSize = 15f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_HINT)
            background = Ui.rounded(Ui.SURFACE, c.dp(22).toFloat(), Ui.BORDER, c.dp(1))
            setPadding(c.dp(16), 0, c.dp(16), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                    rebuild()
                }
            })
        }
        col.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, c.dp(44)).apply {
            leftMargin = c.dp(16); rightMargin = c.dp(16); topMargin = c.dp(4)
        })

        // Count + 显示系统应用
        val opts = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(18), c.dp(6), c.dp(10), c.dp(4))
        }
        countText = c.text("", 13f, Ui.TEXT_SUB)
        opts.addView(countText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        systemBox = CheckBox(c).apply {
            text = c.getString(R.string.zfy_show_system_apps)
            textSize = 13f
            setTextColor(Ui.TEXT_SUB)
            isChecked = showSystem
            tint()
            setOnCheckedChangeListener { _, checked ->
                this@AppPickerDesign.showSystem = checked
                requests.trySend(Request.ToggleSystem)
                rebuild()
            }
        }
        opts.addView(systemBox)
        col.addView(opts)

        // List + side index
        val frame = FrameLayout(c)
        list = ListView(c).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = this@AppPickerDesign.adapter
            setPadding(0, 0, c.dp(22), c.dp(16))
            clipToPadding = false
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                    if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) hideKeyboard(search)
                }
                override fun onScroll(v: AbsListView?, a: Int, b: Int, d: Int) = Unit
            })
        }
        frame.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        indexBar = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(c.dp(2), c.dp(4), c.dp(2), c.dp(4))
        }
        frame.addView(indexBar, FrameLayout.LayoutParams(c.dp(22), FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL))

        emptyText = c.text(c.getString(R.string.zfy_no_apps_found), 14f, Ui.TEXT_SUB).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        frame.addView(emptyText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            c.dp(120), Gravity.TOP))
        progress = ProgressBar(c)
        frame.addView(progress, FrameLayout.LayoutParams(c.dp(40), c.dp(40), Gravity.CENTER))

        col.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = c.dp(4)
        })

        root = col
        updateCount()
    }

    private fun CheckBox.tint() {
        if (Build.VERSION.SDK_INT >= 21) {
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Ui.BLUE, Ui.MUTED)
            )
        }
    }

    private fun hideKeyboard(v: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    suspend fun setApps(apps: List<PickApp>) = withContext(Dispatchers.Main) {
        all = apps
        progress.visibility = View.GONE
        rebuild()
    }

    private fun updateCount() {
        countText.text = context.getString(R.string.zfy_selected_count, selected.size)
    }

    private fun rebuild() {
        val visible = all.asSequence()
            .filter { showSystem || it.userApp || it.pkg in selected }
            .filter { query.isEmpty() || it.matches(query) }
            .sortedWith(compareBy<PickApp>({ if (it.letter == '#') 1 else 0 }, { it.letter }, { it.sortKey }))
            .toList()
        val out = ArrayList<Row>(visible.size + 27)
        var last: Char? = null
        for (a in visible) {
            if (a.letter != last) {
                out.add(Row.Header(a.letter)); last = a.letter
            }
            out.add(Row.App(a))
        }
        rows = out
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (out.isEmpty() && progress.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        buildIndex()
        updateCount()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildIndex() {
        indexBar.removeAllViews()
        val letters = rows.filterIsInstance<Row.Header>().map { it.letter }
        if (letters.size < 2) return
        letters.forEach { l ->
            indexBar.addView(context.text(l.toString(), 10f, Ui.BLUE_DARK, true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(16)))
        }
        indexBar.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val i = (e.y / v.height * letters.size).toInt().coerceIn(0, letters.size - 1)
                    val target = letters[i]
                    val pos = rows.indexOfFirst { it is Row.Header && it.letter == target }
                    if (pos >= 0) list.setSelection(pos)
                    v.setBackgroundColor(0x141E7BEF)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.setBackgroundColor(Color.TRANSPARENT); true
                }
                else -> false
            }
        }
    }

    private fun loadIcon(app: PickApp, view: ImageView) {
        iconCache[app.pkg]?.let { view.setImageDrawable(it); return }
        view.setImageDrawable(null)
        view.tag = app.pkg
        iconLoader.execute {
            val d = runCatching { app.info.loadIcon(pm) }.getOrNull() ?: return@execute
            view.post {
                iconCache[app.pkg] = d
                if (view.tag == app.pkg) view.setImageDrawable(d)
            }
        }
    }

    fun close() {
        iconLoader.shutdownNow()
    }

    private class AppHolder(val icon: ImageView, val label: TextView, val pkg: TextView, val box: CheckBox)

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = rows[position] is Row.App

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val c = context
            return when (val r = rows[position]) {
                is Row.Header -> {
                    val tv = (convertView as? TextView) ?: c.text("", 13f, Ui.BLUE_DARK, true).apply {
                        setPadding(c.dp(20), c.dp(12), c.dp(16), c.dp(4))
                    }
                    tv.text = r.letter.toString()
                    tv
                }
                is Row.App -> {
                    val v = convertView ?: createAppRow()
                    val h = v.tag as AppHolder
                    val a = r.app
                    h.label.text = a.label
                    h.pkg.text = a.pkg
                    h.box.setOnCheckedChangeListener(null)
                    h.box.isChecked = a.pkg in selected
                    h.box.setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(a.pkg) else selected.remove(a.pkg)
                        updateCount()
                    }
                    v.setOnClickListener { h.box.isChecked = !h.box.isChecked }
                    loadIcon(a, h.icon)
                    v
                }
            }
        }

        private fun createAppRow(): View {
            val c = context
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.SURFACE, c.dp(12).toFloat())
                setPadding(c.dp(12), c.dp(8), c.dp(6), c.dp(8))
            }
            val icon = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            row.addView(icon, LinearLayout.LayoutParams(c.dp(40), c.dp(40)))
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val label = c.text("", 15f, Ui.TEXT).apply {
                setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            }
            val pkg = c.text("", 11f, Ui.TEXT_HINT).apply {
                setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            texts.addView(label); texts.addView(pkg)
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = c.dp(12)
            })
            val box = CheckBox(c).apply {
                tint()
                isFocusable = false
            }
            row.addView(box)
            val wrap = FrameLayout(c).apply {
                setPadding(c.dp(12), c.dp(3), c.dp(4), c.dp(3))
                addView(row)
            }
            wrap.tag = AppHolder(icon, label, pkg, box)
            return wrap
        }
    }
}
