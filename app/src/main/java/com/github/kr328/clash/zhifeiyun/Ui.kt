package com.github.kr328.clash.zhifeiyun

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

object Ui {
    const val BLUE = 0xFF1E7BEF.toInt()
    const val BLUE_DARK = 0xFF1565C0.toInt()
    const val GREEN = 0xFF22B573.toInt()
    const val GREY_BG = 0xFFF3F6FB.toInt()
    const val TEXT = 0xFF1F2933.toInt()
    const val TEXT_SUB = 0xFF6B7785.toInt()

    fun Context.dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    fun Context.text(value: CharSequence, sizeSp: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokeColor != null) setStroke(strokePx, strokeColor)
        }

    fun Context.primaryButton(label: String, color: Int = BLUE): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = rounded(color, dp(24).toFloat())
        gravity = Gravity.CENTER
        stateListAnimator = null
        minHeight = dp(48)
    }

    fun Context.outlineButton(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(BLUE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = rounded(Color.WHITE, dp(24).toFloat(), BLUE, dp(1))
        stateListAnimator = null
        minHeight = dp(44)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${bytes} B" else String.format("%.2f %s", v, units[i])
    }
}
