package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.zhifeiyun.AccountStore
import com.github.kr328.clash.zhifeiyun.NodePickerSheet
import com.github.kr328.clash.zhifeiyun.PanelClient
import com.github.kr328.clash.zhifeiyun.PanelParser
import com.github.kr328.clash.zhifeiyun.ProxyMode
import com.github.kr328.clash.zhifeiyun.ProxyModeManager
import com.github.kr328.clash.zhifeiyun.SimpleMainDesign
import com.github.kr328.clash.zhifeiyun.SubscriptionManager
import com.github.kr328.clash.zhifeiyun.ThemeMode
import com.github.kr328.clash.zhifeiyun.Ui
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.util.ApplicationObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity<SimpleMainDesign>() {
    private val store by lazy { AccountStore(this) }
    private var mainGroup: String? = null
    private var connecting = false

    override suspend fun main() {
        if (!store.loggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val design = SimpleMainDesign(this)
        setContentDesign(design)

        refreshAll(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            if (!store.loggedIn) {
                                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                                finish()
                            } else refreshAll(design)
                        }
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged,
                        Event.ProfileUpdateCompleted -> refreshAll(design)
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        SimpleMainDesign.Request.Toggle -> {
                            if (clashRunning) stopClashService() else design.startClash()
                        }
                        SimpleMainDesign.Request.OpenNodes -> showNodes(design)
                        SimpleMainDesign.Request.Renew -> openShop(design)
                        SimpleMainDesign.Request.Advanced ->
                            startActivity(Intent(this@MainActivity, AdvancedActivity::class.java))
                        SimpleMainDesign.Request.Logout -> confirmLogout()
                        SimpleMainDesign.Request.Refresh -> manualRefresh(design)
                        SimpleMainDesign.Request.ModeRule -> switchMode(design, ProxyMode.Rule)
                        SimpleMainDesign.Request.ModeGlobal -> switchMode(design, ProxyMode.Global)
                        SimpleMainDesign.Request.TogglePerApp -> setPerApp(design, design.perAppChecked)
                        SimpleMainDesign.Request.PickApps ->
                            startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
                        // UI only: recreates the screens, the VPN service keeps running untouched.
                        SimpleMainDesign.Request.ToggleTheme ->
                            ThemeMode.toggle(ApplicationObserver.createdActivities)
                        SimpleMainDesign.Request.FollowSystemTheme -> {
                            Toast.makeText(applicationContext, R.string.zfy_theme_follow_system, Toast.LENGTH_SHORT).show()
                            ThemeMode.set(DarkMode.Auto, ApplicationObserver.createdActivities)
                        }
                    }
                }
            }
        }
    }

    private suspend fun refreshAll(design: SimpleMainDesign) {
        design.setState(
            when {
                clashRunning -> SimpleMainDesign.State.Connected
                connecting -> SimpleMainDesign.State.Connecting
                else -> SimpleMainDesign.State.Disconnected
            }
        )
        refreshMode(design)
        refreshAccount(design)
        refreshNode(design)
    }

    private suspend fun refreshMode(design: SimpleMainDesign) {
        val mode = ProxyModeManager.current(this)
        val perApp = ProxyModeManager.perApp(this)
        val icons = if (perApp) withContext(Dispatchers.IO) { selectedAppIcons() } else emptyList()
        design.setMode(mode, perApp, icons)
    }

    /** Icons of the selected (still installed) apps, sorted by label. */
    private fun selectedAppIcons(): List<Drawable> {
        val pm = packageManager
        return ProxyModeManager.selectedApps(this).mapNotNull { pkg ->
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                ai.loadLabel(pm).toString() to ai.loadIcon(pm)
            }.getOrNull()
        }.sortedBy { it.first }.map { it.second }
    }

    /** 分流 / 全局: remembered as the last proxy mode; also used by selected apps when per-app is on. */
    private suspend fun switchMode(design: SimpleMainDesign, mode: ProxyMode) {
        if (mode == ProxyModeManager.current(this)) return
        val perApp = ProxyModeManager.perApp(this)
        val restart = ProxyModeManager.apply(this, mode, perApp)
        if (clashRunning && restart) reconnect(design)
        refreshAll(design)
    }

    /** 仅代理所选应用 on/off; keeps the current 分流/全局 choice. */
    private suspend fun setPerApp(design: SimpleMainDesign, on: Boolean) {
        if (on == ProxyModeManager.perApp(this)) return
        val count = withContext(Dispatchers.IO) { ProxyModeManager.selectedApps(this@MainActivity).size }
        val restart = ProxyModeManager.apply(this, ProxyModeManager.current(this), on)
        refreshMode(design)
        if (on && count == 0) {
            Toast.makeText(this, R.string.zfy_no_apps_selected, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        if (clashRunning && restart) reconnect(design)
        refreshAll(design)
    }

    private suspend fun reconnect(design: SimpleMainDesign) {
        Toast.makeText(this, R.string.zfy_reconnecting, Toast.LENGTH_SHORT).show()
        connecting = true
        design.setState(SimpleMainDesign.State.Connecting)
        ProxyModeManager.restartVpn(this)
        connecting = false
    }

    private suspend fun openShop(design: SimpleMainDesign) {
        val result = startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            Intent(this, ShopActivity::class.java)
        )
        if (result.resultCode == RESULT_OK) manualRefresh(design)
    }

    private suspend fun refreshAccount(design: SimpleMainDesign) {
        val profile = SubscriptionManager.queryProfile(this)
        var remaining: String? = store.htmlRemaining
        var expire: String? = store.htmlExpire
        if (profile != null && profile.total > 0) {
            remaining = Ui.formatBytes(profile.total - profile.upload - profile.download)
        }
        if (profile != null && profile.expire > 0) {
            val ms = if (profile.expire > 100_000_000_000L) profile.expire else profile.expire * 1000
            expire = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ms))
        }
        design.setAccount(store.email, remaining, expire?.take(10))
    }

    private suspend fun refreshNode(design: SimpleMainDesign) {
        if (!clashRunning) {
            mainGroup = null
            design.setNode(getString(R.string.zfy_node_auto))
            return
        }
        try {
            val group = withClash { queryProxyGroupNames(true) }.firstOrNull { it != "GLOBAL" }
                ?: withClash { queryProxyGroupNames(true) }.firstOrNull()
            mainGroup = group
            if (group == null) {
                design.setNode(getString(R.string.zfy_node_auto)); return
            }
            var g = withClash { queryProxyGroup(group, ProxySort.Default) }

            if (!store.autoNodeApplied && g.proxies.isNotEmpty()) {
                val auto = g.proxies.firstOrNull { it.type == "URLTest" }
                    ?: g.proxies.firstOrNull { it.type == "Fallback" || it.type == "LoadBalance" }
                if (auto != null && g.now != auto.name) {
                    withClash { patchSelector(group, auto.name) }
                    g = withClash { queryProxyGroup(group, ProxySort.Default) }
                }
                store.autoNodeApplied = true
            }

            // 全局 mode: route GLOBAL through the main (auto/selected) group, not DIRECT.
            if (ProxyModeManager.current(this) == ProxyMode.Global) {
                runCatching {
                    val mode = withClash { queryTunnelState().mode }
                    if (mode == TunnelState.Mode.Global) {
                        val global = withClash { queryProxyGroup("GLOBAL", ProxySort.Default) }
                        if (global.now != group && global.proxies.any { it.name == group }) {
                            withClash { patchSelector("GLOBAL", group) }
                        }
                    }
                }
            }

            val now = g.proxies.firstOrNull { it.name == g.now }
            var label = now?.title?.ifBlank { null } ?: g.now
            if (now != null && now.isGroup) {
                val inner = runCatching { withClash { queryProxyGroup(now.name, ProxySort.Default) } }.getOrNull()
                if (inner != null && inner.now.isNotBlank()) label = "$label → ${inner.now}"
            }
            design.setNode(label.ifBlank { getString(R.string.zfy_node_auto) })
        } catch (e: Exception) {
            design.setNode(getString(R.string.zfy_node_auto))
        }
    }

    private suspend fun showNodes(design: SimpleMainDesign) {
        if (!clashRunning) {
            Toast.makeText(this, R.string.zfy_connect_first, Toast.LENGTH_SHORT).show()
            return
        }
        val group = mainGroup ?: run { refreshNode(design); mainGroup } ?: run {
            Toast.makeText(this, R.string.zfy_no_nodes, Toast.LENGTH_SHORT).show(); return
        }
        val g = runCatching { withClash { queryProxyGroup(group, ProxySort.Default) } }.getOrNull() ?: return
        if (g.proxies.isEmpty()) {
            Toast.makeText(this, R.string.zfy_no_nodes, Toast.LENGTH_SHORT).show(); return
        }
        withContext(Dispatchers.Main) {
            NodePickerSheet(
                this@MainActivity, this@MainActivity,
                onSelect = { name ->
                    this@MainActivity.launch {
                        runCatching { withClash { patchSelector(group, name) } }
                        refreshNode(design)
                    }
                },
                onSpeedTest = {
                    runCatching { withClash { healthCheck(group) } }
                    runCatching { withClash { queryProxyGroup(group, ProxySort.Default) } }.getOrNull()?.proxies
                },
            ).show(g.proxies, g.now)
        }
    }

    private suspend fun manualRefresh(design: SimpleMainDesign) {
        Toast.makeText(this, R.string.zfy_refreshing, Toast.LENGTH_SHORT).show()
        // Best effort: re-read the /user page with the saved session (may have expired).
        withContext(Dispatchers.IO) {
            runCatching {
                val client = PanelClient(store.cookies.toMutableMap())
                val html = client.fetchUserPage()
                if (html != null) {
                    PanelParser.parseRemainingTraffic(html)?.let { store.htmlRemaining = it }
                    PanelParser.parseExpire(html)?.let { store.htmlExpire = it }
                    val sub = PanelParser.parseClashSubUrl(html)
                    if (sub != null && sub != store.subUrl) {
                        store.subUrl = sub
                        withContext(Dispatchers.Main) {
                            runCatching { SubscriptionManager.importSubscription(this@MainActivity, sub) }
                        }
                    }
                    store.cookies = client.cookieMap
                }
            }
        }
        SubscriptionManager.refresh(this)
        refreshAll(design)
    }

    private suspend fun SimpleMainDesign.startClash() {
        var active = SubscriptionManager.queryProfile(this@MainActivity)
        if (active == null || !active.imported) {
            val url = store.subUrl
            if (url != null) {
                connecting = true
                setState(SimpleMainDesign.State.Connecting)
                active = runCatching { SubscriptionManager.importSubscription(this@MainActivity, url) }.getOrNull()
                connecting = false
            }
        }
        if (active == null || !active.imported) {
            setState(SimpleMainDesign.State.Disconnected)
            Toast.makeText(this@MainActivity, R.string.zfy_sub_import_failed, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { ProxyModeManager.applyStored(this@MainActivity) }
        val vpnRequest = startClashService()
        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )
                if (result.resultCode == RESULT_OK) startClashService()
            }
        } catch (e: Exception) {
            Toast.makeText(this@MainActivity, R.string.zfy_vpn_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.zfy_logout)
            .setMessage(R.string.zfy_logout_confirm)
            .setPositiveButton(R.string.zfy_logout) { _, _ ->
                launch {
                    if (clashRunning) stopClashService()
                    SubscriptionManager.logout(this@MainActivity)
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val launcher = registerForActivityResult(RequestPermission()) { }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
