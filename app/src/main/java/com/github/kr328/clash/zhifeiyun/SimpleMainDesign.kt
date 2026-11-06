package com.github.kr328.clash.zhifeiyun

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    enum class Request { Toggle, OpenNodes, Renew, Advanced, Logout, Refresh, ModeRule, ModeGlobal, ModeApps, PickApps }

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
    private val appsEntryText: TextView

    override val root: View

    init {
        val c = context
        val col = LinearLayout(c).apply {
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
        header.addView(c.text(c.getString(R.string.zfy_advanced), 15f, Ui.BLUE).apply {
            setPadding(c.dp(12), c.dp(8), c.dp(4), c.dp(8))
            setOnClickListener { requests.trySend(Request.Advanced) }
        })
        col.addView(header)

        // Big connect button
        connectButton = c.text(c.getString(R.string.zfy_connect), 24f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { requests.trySend(Request.Toggle) }
        }
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

        // Mode selector: 分流 / 全局 / 应用
        val seg = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(Color.WHITE, c.dp(22).toFloat(), 0xFFE1E7EF.toInt(), c.dp(1))
            setPadding(c.dp(4), c.dp(4), c.dp(4), c.dp(4))
            elevation = c.dp(1).toFloat()
        }
        listOf(
            R.string.zfy_mode_rule to Request.ModeRule,
            R.string.zfy_mode_global to Request.ModeGlobal,
            R.string.zfy_mode_apps to Request.ModeApps,
        ).forEach { (label, req) ->
            val tab = c.text(c.getString(label), 15f, Ui.TEXT_SUB, true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { requests.trySend(req) }
            }
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

        appsEntry = card(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(18), c.dp(12), c.dp(18), c.dp(12))
            setOnClickListener { requests.trySend(Request.PickApps) }
            visibility = View.GONE
        }
        appsEntryText = c.text("", 15f, Ui.TEXT, true)
        appsEntry.addView(appsEntryText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        appsEntry.addView(c.text("›", 20f, Ui.BLUE))
        col.addView(appsEntry, cardParams(c, 12))

        // Node card
        val nodeCard = card(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { requests.trySend(Request.OpenNodes) }
        }
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
        }, LinearLayout.LayoutParams(0, c.dp(46), 1f).apply { rightMargin = c.dp(6) })
        btnRow.addView(c.outlineButton(c.getString(R.string.zfy_refresh)).apply {
            setOnClickListener { requests.trySend(Request.Refresh) }
        }, LinearLayout.LayoutParams(0, c.dp(46), 1f).apply { leftMargin = c.dp(6) })
        acct.addView(btnRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(14) })
        col.addView(acct, cardParams(c, 14))

        // Logout
        col.addView(c.text(c.getString(R.string.zfy_logout), 14f, Ui.TEXT_SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, c.dp(20), 0, c.dp(8))
            setOnClickListener { requests.trySend(Request.Logout) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root = ScrollView(c).apply {
            setBackgroundColor(Ui.GREY_BG)
            isFillViewport = true
            fitsSystemWindows = true
            addView(col)
        }

        applyState(State.Disconnected)
        applyMode(ProxyMode.Rule, 0)
    }

    private fun applyMode(mode: ProxyMode, appCount: Int) {
        val c = context
        modeTabs.forEachIndexed { i, tab ->
            val on = i == mode.ordinal
            tab.background = if (on) Ui.rounded(Ui.BLUE, c.dp(19).toFloat()) else null
            tab.setTextColor(if (on) Color.WHITE else Ui.TEXT_SUB)
        }
        modeHint.text = c.getString(when (mode) {
            ProxyMode.Rule -> R.string.zfy_mode_rule_hint
            ProxyMode.Global -> R.string.zfy_mode_global_hint
            ProxyMode.Apps -> R.string.zfy_mode_apps_hint
        })
        appsEntry.visibility = if (mode == ProxyMode.Apps) View.VISIBLE else View.GONE
        appsEntryText.text = c.getString(R.string.zfy_pick_apps_entry, appCount)
    }

    suspend fun setMode(mode: ProxyMode, appCount: Int) = withContext(Dispatchers.Main) { applyMode(mode, appCount) }

    private fun card(c: Context) = LinearLayout(c).apply {
        background = Ui.rounded(Color.WHITE, c.dp(16).toFloat())
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
            State.Connecting -> Triple(0xFF90A4AE.toInt(), R.string.zfy_connecting, R.string.zfy_status_connecting)
            State.Connected -> Triple(Ui.GREEN, R.string.zfy_disconnect, R.string.zfy_status_connected)
        }
        connectButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        connectRing.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((color and 0x00FFFFFF) or 0x33000000)
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
