package com.github.kr328.clash.zhifeiyun

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore

/**
 * Light / dark choice for the whole app.
 *
 * The single source of truth is CMFA's own "dark_mode" preference (UiStore.darkMode), so the
 * home toggle and 高级 → 设置 → 暗黑模式 always agree. DarkMode.Auto (the default until the user
 * taps the toggle) follows the system; a tap stores ForceLight / ForceDark. The choice is also
 * mirrored into AppCompatDelegate so values-night resources and dialogs match.
 */
object ThemeMode {
    @Volatile
    private var cached: DarkMode? = null

    private fun context(): Context = Global.application

    var mode: DarkMode
        get() = cached ?: UiStore(context()).darkMode.also { cached = it }
        private set(value) {
            UiStore(context()).darkMode = value
            cached = value
        }

    fun systemNight(): Boolean =
        Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /** Whether the custom (智飞云) screens should currently draw their dark palette. */
    val isNight: Boolean
        get() = when (mode) {
            DarkMode.ForceDark -> true
            DarkMode.ForceLight -> false
            DarkMode.Auto -> systemNight()
        }

    private fun delegateMode(m: DarkMode) = when (m) {
        DarkMode.ForceDark -> AppCompatDelegate.MODE_NIGHT_YES
        DarkMode.ForceLight -> AppCompatDelegate.MODE_NIGHT_NO
        DarkMode.Auto -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Called once at app start (UI process) to mirror the stored choice into AppCompat. */
    fun init() {
        cached = null
        val m = mode
        if (AppCompatDelegate.getDefaultNightMode() != delegateMode(m)) {
            AppCompatDelegate.setDefaultNightMode(delegateMode(m))
        }
    }

    /** Re-read the preference after it was changed elsewhere (高级 settings screen). */
    fun reload() {
        cached = null
        val d = delegateMode(mode)
        if (AppCompatDelegate.getDefaultNightMode() != d) AppCompatDelegate.setDefaultNightMode(d)
    }

    /** Explicit user choice from the home toggle; recreates every open screen right away. */
    fun set(newMode: DarkMode, activities: Collection<Activity>) {
        if (newMode == mode) return
        mode = newMode
        val d = delegateMode(newMode)
        if (AppCompatDelegate.getDefaultNightMode() != d) AppCompatDelegate.setDefaultNightMode(d)
        activities.toList().forEach { if (!it.isFinishing) it.recreate() }
    }

    /** Set before a theme change from the home toggle so the recreated home screen focuses it again (D-pad). */
    @Volatile
    var refocusToggle = false

    /**
     * TV remote: OK on the toggle cycles 浅色 → 深色 → 跟随系统 → 浅色 …
     * From 跟随系统 it goes to the opposite of what is shown now. Returns the new mode.
     */
    fun cycle(activities: Collection<Activity>): DarkMode {
        val next = when (mode) {
            DarkMode.ForceLight -> DarkMode.ForceDark
            DarkMode.ForceDark -> DarkMode.Auto
            DarkMode.Auto -> if (isNight) DarkMode.ForceLight else DarkMode.ForceDark
        }
        refocusToggle = true
        set(next, activities)
        return next
    }

    /** Tap on the sun/moon button: flip whatever is currently shown to the opposite. */
    fun toggle(activities: Collection<Activity>) {
        refocusToggle = true
        set(if (isNight) DarkMode.ForceLight else DarkMode.ForceDark, activities)
    }
}
