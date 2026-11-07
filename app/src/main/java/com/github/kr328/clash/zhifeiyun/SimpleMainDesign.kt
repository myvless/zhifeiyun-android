package com.github.kr328.clash.zhifeiyun

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.github.kr328.clash.R
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.zhifeiyun.Ui.dp
import com.github.kr328.clash.zhifeiyun.Ui.outlineButton
import com.github.kr328.clash.zhifeiyun.Ui.primaryButton
import com.github.kr328.clash.zhifeiyun.Ui.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Beginner-friendly home screen, built in code (no data binding). */
class SimpleMainDesign(context: Context) : Design<SimpleMainDesign.Request>(context) {
    enum class Request { Toggle, OpenNodes, Renew, Advanced, Logout, Refresh, ModeRule, ModeGlobal, TogglePerApp, PickApps, ToggleTheme, FollowSystemTheme, CycleTheme }

    enum class State { Disconnected, Connecting, Connected }

    private val connectButton: TextView
    private val connectRing: LinearLayout
    private val statusText: TextView
    private val nodeText: TextView
    private val emailText: TextView
    private val remainText: TextView
    private val expireText: TextView
    private val modeTabs = ArrayList<TextView>()
    private val modeHint: TextView
    private val appsEntry: LinearLayout
    private val appsDivider: View
    private val appIcons: AppIconStrip
    private lateinit var perAppSwitch: SwitchCompat
    private var bindingSwitch = false

    /** Whether the per-app switch is currently on (read by the activity on TogglePerApp). */
    val perAppChecked: Boolean get() = perAppSwitch.isChecked

    override val root: View

    init {
        val c = context
        val tv = Tv.isTv(c)
        // Content is limited to ~600dp and centred on wide screens (TV, tablets, landscape).
        val col = Tv.maxWidthColumn(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(20), c.dp(12), c.dp(20), c.dp(24))
        }

        // Header: logo + name + 高级
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(c).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
        }, LinearLayout.LayoutParams(c.dp(44), c.dp(44)))
        header.addView(c.text(c.getString(R.string.zfy_app_name), 22f, Ui.BLUE_DARK, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = c.dp(6) })
        // Light/dark toggle: shows the moon in light mode (tap → dark) and the sun in dark mode.
        val themeToggle = ImageView(c).apply {
            setImageResource(if (Ui.night) R.drawable.ic_zfy_sun else R.drawable.ic_zfy_moon)
            imageTintList = ColorStateList.valueOf(if (Ui.night) 0xFFFFC94D.toInt() else Ui.BLUE_DARK)
            background = Ui.rounded(Ui.SURFACE, c.dp(18).toFloat(), Ui.BORDER, c.dp(1))
            setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
            contentDescription = c.getString(
                if (tv) R.string.zfy_theme_cycle_tv
                else if (Ui.night) R.string.zfy_theme_to_light else R.string.zfy_theme_to_dark
            )
            // Phone: tap flips 浅色/深色, long-press = 跟随系统. TV remote: OK cycles
            // 浅色 → 深色 → 跟随系统 (long-press of OK still works too).
            setOnClickListener { requests.trySend(if (tv) Request.CycleTheme else Request.ToggleTheme) }
            setOnLongClickListener { requests.trySend(Request.FollowSystemTheme); true }
        }
        Tv.focusRing(themeToggle, oval = true, scale = 1.12f)
        header.addView(themeToggle, LinearLayout.LayoutParams(c.dp(36), c.dp(36)).apply { leftMargin = c.dp(8) })
        val advanced = c.text(c.getString(R.string.zfy_advanced), 15f, Ui.BLUE).apply {
            setPadding(c.dp(12), c.dp(8), c.dp(12), c.dp(8))
            setOnClickListener { requests.trySend(Request.Advanced) }
        }
        Tv.focusRing(advanced, 10f, scale = 1.06f)
        header.addView(advanced, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = c.dp(4); rightMargin = -c.dp(8) })
        col.addView(header)

        // Big connect button
        connectButton = c.text(c.getString(R.string.zfy_connect), 24f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { requests.trySend(Request.Toggle) }
        }
        Tv.focusRing(connectButton, oval = true, scale = 1.06f)
        connectRing = LinearLayout(c).apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(10), c.dp(10), c.dp(10), c.dp(10))
            addView(connectButton, LinearLayout.LayoutParams(c.dp(170), c.dp(170)))
        }
        col.addView(connectRing, LinearLayout.LayoutParams(c.dp(190), c.dp(190)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = c.dp(28)
        })
        statusText = c.text(c.getString(R.string.zfy_status_disconnected), 15f, Ui.TEXT_SUB).apply {
            gravity = Gravity.CENTER
        }
        col.addView(statusText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(12) })

        // Mode selector: 分流 / 全局 (last choice is remembered and also used for per-app)
        val seg = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(Ui.SURFACE, c.dp(22).toFloat(), Ui.BORDER, c.dp(1))
            setPadding(c.dp(4), c.dp(4), c.dp(4), c.dp(4))
            elevation = c.dp(1).toFloat()
        }
        listOf(
            R.string.zfy_mode_rule to Request.ModeRule,
            R.string.zfy_mode_global to Request.ModeGlobal,
        ).forEach { (label, req) ->
            val tab = c.text(c.getString(label), 15f, Ui.TEXT_SUB, true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { requests.trySend(req) }
            }
            Tv.focusRing(tab, 19f, scale = 1.04f)
            modeTabs.add(tab)
            seg.addView(tab, LinearLayout.LayoutParams(0, c.dp(38), 1f))
        }
        col.addView(seg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = c.dp(22); leftMargin = c.dp(12); rightMargin = c.dp(12)
        })
        modeHint = c.text("", 12f, Ui.TEXT_SUB).apply { gravity = Gravity.CENTER }
        col.addView(modeHint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(8) })

        // Per-app card: 仅代理所选应用 switch + 加速APP row with icons
        val appsCard = card(c).apply {
            orientation = LinearLayout.VERTICAL
            // Rows carry 8dp side padding themselves so the D-pad focus ring has room.
            setPadding(c.dp(10), c.dp(6), c.dp(6), c.dp(6))
        }
        val switchRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
            setOnClickListener { perAppSwitch.toggle() }
        }
        Tv.focusRing(switchRow, 10f, scale = 1f)
        val switchText = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        switchText.addView(c.text(c.getString(R.string.zfy_per_app_title), 15f, Ui.TEXT, true))
        switchText.addView(c.text(c.getString(R.string.zfy_per_app_hint), 12f, Ui.TEXT_SUB).apply {
            setPadding(0, c.dp(2), c.dp(8), 0)
        })
        switchRow.addView(switchText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        perAppSwitch = SwitchCompat(c).apply {
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Ui.BLUE, Ui.SWITCH_THUMB_OFF)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x661E7BEF, Ui.SWITCH_TRACK_OFF)
            )
            setOnCheckedChangeListener { _, _ ->
                if (!bindingSwitch) requests.trySend(Request.TogglePerApp)
            }
            // The whole row is the D-pad target; the switch itself is not a separate stop.
            isFocusable = false
        }
        switchRow.addView(perAppSwitch)
        appsCard.addView(switchRow)

        appsDivider = View(c).apply { setBackgroundColor(Ui.DIVIDER) }
        appsCard.addView(appsDivider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            leftMargin = c.dp(8); rightMargin = c.dp(8)
        })

        appsEntry = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(8), c.dp(12), c.dp(8), c.dp(12))
            setOnClickListener { requests.trySend(Request.PickApps) }
        }
        Tv.focusRing(appsEntry, 10f, scale = 1f)
        appsEntry.addView(c.text(c.getString(R.string.zfy_accel_apps), 15f, Ui.TEXT, true))
        appIcons = AppIconStrip(c)
        appsEntry.addView(appIcons, LinearLayout.LayoutParams(0, c.dp(28), 1f).apply {
            leftMargin = c.dp(12); rightMargin = c.dp(6)
        })
        appsEntry.addView(c.text("›", 22f, Ui.TEXT_SUB))
        appsCard.addView(appsEntry)
        col.addView(appsCard, cardParams(c, 12))

        // Node card
        val nodeCard = card(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { requests.trySend(Request.OpenNodes) }
        }
        Tv.focusRing(nodeCard, 16f, scale = 1.02f)
        val nodeCol = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        nodeCol.addView(c.text(c.getString(R.string.zfy_current_node), 13f, Ui.TEXT_SUB))
        nodeText = c.text(c.getString(R.string.zfy_node_auto), 17f, Ui.TEXT, true).apply {
            setPadding(0, c.dp(4), 0, 0)
        }
        nodeCol.addView(nodeText)
        nodeCard.addView(nodeCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nodeCard.addView(c.text(c.getString(R.string.zfy_switch_node), 14f, Ui.BLUE))
        col.addView(nodeCard, cardParams(c, 16))

        // Account card
        val acct = card(c).apply { orientation = LinearLayout.VERTICAL }
        emailText = c.text("", 13f, Ui.TEXT_SUB)
        acct.addView(emailText)
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        left.addView(c.text(c.getString(R.string.zfy_remaining), 13f, Ui.TEXT_SUB))
        remainText = c.text("—", 18f, Ui.TEXT, true)
        left.addView(remainText)
        val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        right.addView(c.text(c.getString(R.string.zfy_expire), 13f, Ui.TEXT_SUB))
        expireText = c.text("—", 18f, Ui.TEXT, true)
        right.addView(expireText)
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        acct.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(10) })

        val btnRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(c.primaryButton(c.getString(R.string.zfy_renew)).apply {
            setOnClickListener { requests.trySend(Request.Renew) }
            Tv.focusRing(this, 24f)
        }, LinearLayout.LayoutParams(0, c.dp(46), 1f).apply { rightMargin = c.dp(6) })
        btnRow.addView(c.outlineButton(c.getString(R.string.zfy_refresh)).apply {
            setOnClickListener { requests.trySend(Request.Refresh) }
            Tv.focusRing(this, 24f)
        }, LinearLayout.LayoutParams(0, c.dp(46), 1f).apply { leftMargin = c.dp(6) })
        acct.addView(btnRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(14) })
        col.addView(acct, cardParams(c, 14))

        // Logout
        col.addView(c.text(c.getString(R.string.zfy_logout), 14f, Ui.TEXT_SUB).apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(10))
            setOnClickListener { requests.trySend(Request.Logout) }
            Tv.focusRing(this, 10f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = c.dp(10)
        })

        root = ScrollView(c).apply {
            setBackgroundColor(Ui.GREY_BG)
            isFillViewport = true
            fitsSystemWindows = true
            addView(col, Tv.centeredInFrame())
        }

        applyState(State.Disconnected)
        applyMode(ProxyMode.Rule, false, emptyList())

        // D-pad: start on the big connect button (or back on the theme toggle after a theme change).
        if (ThemeMode.refocusToggle) {
            ThemeMode.refocusToggle = false
            Tv.initialFocus(themeToggle)
        } else {
            Tv.initialFocus(connectButton)
        }
    }

    private fun applyMode(mode: ProxyMode, perApp: Boolean, icons: List<Drawable>) {
        val c = context
        modeTabs.forEachIndexed { i, tab ->
            val on = i == mode.ordinal
            tab.background = if (on) Ui.rounded(Ui.BLUE, c.dp(19).toFloat()) else null
            tab.setTextColor(if (on) Color.WHITE else Ui.TEXT_SUB)
            Tv.refreshRing(tab, 19f)
        }
        modeHint.text = c.getString(when (mode) {
            ProxyMode.Rule -> if (perApp) R.string.zfy_mode_rule_hint_apps else R.string.zfy_mode_rule_hint
            ProxyMode.Global -> if (perApp) R.string.zfy_mode_global_hint_apps else R.string.zfy_mode_global_hint
        })
        bindingSwitch = true
        perAppSwitch.isChecked = perApp
        bindingSwitch = false
        appsDivider.visibility = if (perApp) View.VISIBLE else View.GONE
        appsEntry.visibility = if (perApp) View.VISIBLE else View.GONE
        appIcons.setIcons(icons)
    }

    suspend fun setMode(mode: ProxyMode, perApp: Boolean, icons: List<Drawable>) =
        withContext(Dispatchers.Main) { applyMode(mode, perApp, icons) }

    private fun card(c: Context) = LinearLayout(c).apply {
        background = Ui.rounded(Ui.SURFACE, c.dp(16).toFloat())
        setPadding(c.dp(18), c.dp(16), c.dp(18), c.dp(16))
        elevation = c.dp(1).toFloat()
    }

    private fun cardParams(c: Context, top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = c.dp(top) }

    private fun applyState(state: State) {
        val c = context
        val (color, label, status) = when (state) {
            State.Disconnected -> Triple(Ui.BLUE, R.string.zfy_connect, R.string.zfy_status_disconnected)
            State.Connecting -> Triple(Ui.GREY_CONNECTING, R.string.zfy_connecting, R.string.zfy_status_connecting)
            State.Connected -> Triple(Ui.GREEN, R.string.zfy_disconnect, R.string.zfy_status_connected)
        }
        connectButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        Tv.refreshRing(connectButton, oval = true)
        connectRing.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((color and 0x00FFFFFF) or Ui.RING_ALPHA)
        }
        connectButton.text = c.getString(label)
        statusText.text = c.getString(status)
        statusText.setTextColor(if (state == State.Connected) Ui.GREEN else Ui.TEXT_SUB)
    }

    suspend fun setState(state: State) = withContext(Dispatchers.Main) { applyState(state) }

    suspend fun setNode(name: String) = withContext(Dispatchers.Main) { nodeText.text = name }

    suspend fun setAccount(email: String?, remaining: String?, expire: String?) = withContext(Dispatchers.Main) {
        emailText.text = email ?: ""
        remainText.text = remaining ?: "—"
        expireText.text = expire ?: "—"
    }
}
