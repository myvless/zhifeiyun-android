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
    /** True when the dark palette is active (see ThemeMode). Light values are the original look. */
    val night: Boolean get() = ThemeMode.isNight

    private fun pick(light: Long, dark: Long): Int = (if (night) dark else light).toInt()

    // Brand colours stay the same in both modes.
    const val BLUE = 0xFF1E7BEF.toInt()
    const val GREEN = 0xFF22B573.toInt()
    const val ORANGE = 0xFFF59E0B.toInt()
    const val RED = 0xFFEF4444.toInt()
    const val GREY_CONNECTING = 0xFF90A4AE.toInt()

    /** Headings / app name / index letters (darker blue on light, lighter blue on dark). */
    val BLUE_DARK: Int get() = pick(0xFF1565C0, 0xFF6AAAF5)
    /** Screen background. */
    val GREY_BG: Int get() = pick(0xFFF3F6FB, 0xFF0F141A)
    /** Cards, sheets, toolbars, inputs on grey (was plain white). */
    val SURFACE: Int get() = pick(0xFFFFFFFF, 0xFF1A2129)
    /** Inputs / chips sitting on a SURFACE. */
    val SURFACE_ALT: Int get() = pick(0xFFEFF2F6, 0xFF252D37)
    /** Input background on the login page (sits on SURFACE). */
    val INPUT_BG: Int get() = pick(0xFFF3F6FB, 0xFF252D37)
    val DIVIDER: Int get() = pick(0xFFEDF1F6, 0xFF2A323D)
    val BORDER: Int get() = pick(0xFFE1E7EF, 0xFF333C48)
    val TEXT: Int get() = pick(0xFF1F2933, 0xFFE6EAF0)
    val TEXT_SUB: Int get() = pick(0xFF6B7785, 0xFF9AA6B4)
    val TEXT_HINT: Int get() = pick(0xFF9AA5B1, 0xFF6E7A88)
    val MUTED: Int get() = pick(0xFFB0B8C4, 0xFF5E6975)
    val RADIO_OFF: Int get() = pick(0xFFC3CBD6, 0xFF4E5865)
    val HANDLE: Int get() = pick(0xFFD5DCE5, 0xFF3D4653)
    val SELECTED_ROW: Int get() = pick(0xFFEEF5FE, 0xFF1A2C42)
    val SWITCH_THUMB_OFF: Int get() = pick(0xFFF5F7FA, 0xFFB7C0CB)
    val SWITCH_TRACK_OFF: Int get() = pick(0x33000000, 0x33FFFFFF)
    /** Alpha used for the halo ring around the big connect button. */
    val RING_ALPHA: Int get() = pick(0x33000000, 0x40000000)

    /** Status / navigation bar colours and icon contrast for screens not built on BaseActivity. */
    fun applySystemBars(activity: android.app.Activity, color: Int = SURFACE) {
        val w = activity.window
        w.statusBarColor = color
        w.navigationBarColor = color
        val c = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
        c.isAppearanceLightStatusBars = !night
        c.isAppearanceLightNavigationBars = !night
    }

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
        background = rounded(SURFACE, dp(24).toFloat(), BLUE, dp(1))
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
