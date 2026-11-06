package com.github.kr328.clash.zhifeiyun

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import com.github.kr328.clash.R
import com.github.kr328.clash.zhifeiyun.Ui.dp
import com.github.kr328.clash.zhifeiyun.Ui.text

/**
 * A single row of small (24dp) app icons packed densely; when they do not all fit,
 * the last slot shows "+N". Shows "未选择" when empty.
 */
class AppIconStrip(context: Context) : LinearLayout(context) {
    private var icons: List<Drawable> = emptyList()
    private var builtFor: Pair<Int, Int>? = null // (width, iconCount)

    private val iconSize = context.dp(24)
    private val gap = context.dp(4)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    fun setIcons(list: List<Drawable>) {
        icons = list
        builtFor = null
        if (width > 0) rebuild() else requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) post { rebuild() }
    }

    private fun rebuild() {
        val w = width
        if (builtFor == (w to icons.size)) return
        builtFor = w to icons.size
        removeAllViews()

        if (icons.isEmpty()) {
            addView(context.text(context.getString(R.string.zfy_no_apps_short), 13f, Ui.TEXT_SUB))
            return
        }
        val slot = iconSize + gap
        val capacity = ((w + gap) / slot).coerceAtLeast(1)
        val showCount: Int
        val more: Int
        if (icons.size <= capacity) {
            showCount = icons.size; more = 0
        } else {
            showCount = (capacity - 1).coerceAtLeast(0)
            more = icons.size - showCount
        }
        for (i in 0 until showCount) {
            addView(ImageView(context).apply {
                setImageDrawable(icons[i])
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LayoutParams(iconSize, iconSize).apply { if (i > 0) leftMargin = gap })
        }
        if (more > 0) {
            addView(context.text("+$more", 11f, Ui.TEXT_SUB, true).apply {
                gravity = Gravity.CENTER
                minWidth = iconSize
                setPadding(context.dp(3), 0, context.dp(3), 0)
                background = Ui.rounded(0xFFEDF1F6.toInt(), iconSize / 2f)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, iconSize).apply { if (showCount > 0) leftMargin = gap })
        }
    }
}
