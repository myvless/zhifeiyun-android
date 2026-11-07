package com.github.kr328.clash.zhifeiyun

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Android TV / remote-control (D-pad) helpers for the custom 智飞云 screens.
 *
 * Every interactive view gets [focusRing]: it becomes focusable and shows a two-tone ring
 * (accent outside, contrasting colour inside, so it is visible on blue buttons as well as on
 * cards, in light and dark mode) plus a small zoom while it has D-pad focus. In touch mode
 * (phones) buttons never hold focus, so phones look exactly as before.
 */
object Tv {
    /** Ring colour: brand blue on light, light blue on dark. */
    val RING: Int get() = if (Ui.night) 0xFF8EC5FF.toInt() else 0xFF1E7BEF.toInt()
    /** Inner contrast line of the ring. */
    val RING_INNER: Int get() = if (Ui.night) 0xFF0F141A.toInt() else Color.WHITE

    @Volatile
    private var cachedTv: Boolean? = null

    /** True on Android TV / boxes driven by a remote (leanback UI mode or no touchscreen). */
    fun isTv(c: Context): Boolean = cachedTv ?: run {
        val ui = c.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val pm = c.packageManager
        val tv = ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        cachedTv = tv
        tv
    }

    /** The ring itself (always visible). */
    fun ringDrawable(c: Context, radiusPx: Float, oval: Boolean = false): Drawable {
        val d = c.resources.displayMetrics.density
        val outerW = (3 * d).toInt().coerceAtLeast(2)
        val innerW = (2 * d).toInt().coerceAtLeast(1)
        fun ring(color: Int, w: Int, r: Float) = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = r.coerceAtLeast(0f)
            setColor(Color.TRANSPARENT)
            setStroke(w, color)
        }
        return LayerDrawable(arrayOf(ring(RING, outerW, radiusPx), ring(RING_INNER, innerW, radiusPx - outerW))).apply {
            setLayerInset(1, outerW, outerW, outerW, outerW)
        }
    }

    /** Drawable that shows the focus ring only while the view is focused. */
    fun focusDrawable(c: Context, radiusPx: Float, oval: Boolean = false): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ringDrawable(c, radiusPx, oval))
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }

    /**
     * Makes [v] reachable with the D-pad and highlights it while focused.
     * [radiusDp] should match the view's own corner radius; [scale] is the focus zoom (1f = none).
     */
    fun focusRing(v: View, radiusDp: Float = 12f, oval: Boolean = false, scale: Float = 1.04f) {
        val c = v.context
        v.isFocusable = true
        // EditTexts keep focus while typing on phones: only draw their ring on TV.
        if (v is EditText && !isTv(c)) return
        val ring = focusDrawable(c, radiusDp * c.resources.displayMetrics.density, oval)
        if (Build.VERSION.SDK_INT >= 23) {
            v.foreground = ring
        } else {
            // API 21/22: View.foreground only exists on FrameLayout; layer it over the background.
            val bg = v.background
            v.background = if (bg == null) ring else LayerDrawable(arrayOf(bg, ring))
        }
        if (scale != 1f) {
            val prev = v.onFocusChangeListener
            v.setOnFocusChangeListener { view, has ->
                prev?.onFocusChange(view, has)
                val s = if (has && !view.isInTouchMode) scale else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
        }
    }

    /** Re-applies the ring after code replaced the background of a view on API < 23. */
    fun refreshRing(v: View, radiusDp: Float = 12f, oval: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 23) return
        val c = v.context
        val ring = focusDrawable(c, radiusDp * c.resources.displayMetrics.density, oval)
        val bg = v.background
        v.background = if (bg == null) ring else LayerDrawable(arrayOf(bg, ring))
    }

    /** Requests focus on [v] once laid out, when driven by a remote / keyboard (not in touch mode). */
    fun initialFocus(v: View, always: Boolean = false) {
        v.post {
            if (always || isTv(v.context) || !v.isInTouchMode) v.requestFocus()
        }
    }

    fun ensureId(v: View): Int {
        if (v.id == View.NO_ID) v.id = View.generateViewId()
        return v.id
    }

    /** Chains nextFocusDown/Up through [views] in order (skips nothing; call after they exist). */
    fun chainVertical(vararg views: View) {
        for (i in views.indices) {
            if (i + 1 < views.size) views[i].nextFocusDownId = ensureId(views[i + 1])
            if (i > 0) views[i].nextFocusUpId = ensureId(views[i - 1])
        }
    }

    /** Max content width on wide screens (TV / tablets / landscape). */
    const val MAX_CONTENT_DP = 600

    /** Vertical LinearLayout whose width never exceeds [maxWidthPx]; centre it in its parent. */
    class MaxWidthLinearLayout(c: Context, private val maxWidthPx: Int) : LinearLayout(c) {
        init { orientation = VERTICAL }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(widthMeasureSpec)
            val mode = MeasureSpec.getMode(widthMeasureSpec)
            val spec = if (maxWidthPx in 1 until size && mode != MeasureSpec.UNSPECIFIED)
                MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY) else widthMeasureSpec
            super.onMeasure(spec, heightMeasureSpec)
        }
    }

    fun maxWidthColumn(c: Context, maxDp: Int = MAX_CONTENT_DP): MaxWidthLinearLayout =
        MaxWidthLinearLayout(c, (maxDp * c.resources.displayMetrics.density).toInt())

    /** Layout params for a [MaxWidthLinearLayout] inside a ScrollView / FrameLayout: centred horizontally. */
    fun centeredInFrame(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height,
            android.view.Gravity.CENTER_HORIZONTAL)

    /** Layout params for a [MaxWidthLinearLayout] inside a vertical LinearLayout: centred horizontally. */
    fun centeredInColumn(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, weight).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
}
