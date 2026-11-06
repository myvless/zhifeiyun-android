package com.github.kr328.clash.zhifeiyun

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Home-screen routing choice: 分流 / 全局. Independent of the per-app switch. */
enum class ProxyMode { Rule, Global }

/**
 * Two independent settings:
 *  - the last proxy mode the user chose (分流 = Clash rule, 全局 = Clash global), applied via
 *    the persist override slot (same as 高级 › 覆写 › 模式);
 *  - "仅代理所选应用" (per-app VPN), applied via ServiceStore.accessControlMode /
 *    accessControlPackages (same as 高级 › 网络 › 访问控制).
 * With per-app on, the selected apps use whichever mode was chosen last.
 */
object ProxyModeManager {
    private const val PREFS = "zhifeiyun_settings"
    private const val KEY_MODE = "proxy_mode"           // "Rule" | "Global" ("Apps" in v1.1.0)
    private const val KEY_PER_APP = "per_app_enabled"
    private const val KEY_SHOW_SYSTEM = "picker_show_system"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Last chosen 分流/全局 mode. v1.1.0's "Apps" value meant rule mode. */
    fun current(c: Context): ProxyMode = when (prefs(c).getString(KEY_MODE, null)) {
        "Global" -> ProxyMode.Global
        else -> ProxyMode.Rule
    }

    fun perApp(c: Context): Boolean {
        val p = prefs(c)
        if (p.contains(KEY_PER_APP)) return p.getBoolean(KEY_PER_APP, false)
        // Upgrade from v1.1.0 (mode "Apps") or an access-control setup made in 高级.
        if (p.getString(KEY_MODE, null) == "Apps") return true
        return ServiceStore(c).accessControlMode == AccessControlMode.AcceptSelected
    }

    fun showSystemApps(c: Context): Boolean = prefs(c).getBoolean(KEY_SHOW_SYSTEM, false)

    fun setShowSystemApps(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(KEY_SHOW_SYSTEM, v).apply()
    }

    fun selectedApps(c: Context): Set<String> = ServiceStore(c).accessControlPackages

    fun saveSelectedApps(c: Context, set: Set<String>) {
        ServiceStore(c).accessControlPackages = set
    }

    /**
     * Persists [mode] / [perApp] and pushes them to the service/core.
     * @return true when the VPN must be restarted for the change to take effect
     *         (access control is read only when the TUN interface is built).
     */
    suspend fun apply(c: Context, mode: ProxyMode, perApp: Boolean): Boolean {
        val restart = withContext(Dispatchers.IO) {
            prefs(c).edit()
                .putString(KEY_MODE, mode.name)
                .putBoolean(KEY_PER_APP, perApp)
                .commit()
            val srv = ServiceStore(c)
            val old = srv.accessControlMode
            val new = when {
                perApp -> AccessControlMode.AcceptSelected
                old == AccessControlMode.AcceptSelected -> AccessControlMode.AcceptAll
                else -> old // keep a DenySelected list configured in 高级
            }
            if (new != old) srv.accessControlMode = new
            new != old
        }

        val tunnel = if (mode == ProxyMode.Global) TunnelState.Mode.Global else TunnelState.Mode.Rule
        runCatching {
            withClash {
                val p = queryOverride(Clash.OverrideSlot.Persist)
                if (p.mode != tunnel) {
                    p.mode = tunnel
                    patchOverride(Clash.OverrideSlot.Persist, p)
                }
                // A session-level mode set in 高级 › 代理 would win over ours; drop it.
                val s = queryOverride(Clash.OverrideSlot.Session)
                if (s.mode != null) {
                    s.mode = null
                    patchOverride(Clash.OverrideSlot.Session, s)
                }
            }
        }
        return restart
    }

    /** Re-applies the stored settings (used before starting the VPN). */
    suspend fun applyStored(c: Context): Boolean = apply(c, current(c), perApp(c))

    /** Stop and start the VPN so a new access-control list is applied. */
    suspend fun restartVpn(c: Context) {
        c.stopClashService()
        withTimeoutOrNull(8000) {
            while (Remote.broadcasts.clashRunning) delay(200)
        }
        delay(300)
        withContext(Dispatchers.Main) { c.startClashService() }
    }
}
