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

/** Home-screen routing choice: 分流 / 全局 / 应用. */
enum class ProxyMode { Rule, Global, Apps }

/**
 * Maps the home-screen choice onto CMFA's own mechanisms:
 *  - Clash tunnel mode via the persist override slot (same as 高级 › 覆写 › 模式),
 *  - per-app VPN via ServiceStore.accessControlMode / accessControlPackages
 *    (same as 高级 › 网络 › 访问控制).
 */
object ProxyModeManager {
    private const val PREFS = "zhifeiyun_settings"
    private const val KEY_MODE = "proxy_mode"
    private const val KEY_SHOW_SYSTEM = "picker_show_system"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(c: Context): ProxyMode {
        val saved = prefs(c).getString(KEY_MODE, null)
        if (saved != null) return runCatching { ProxyMode.valueOf(saved) }.getOrDefault(ProxyMode.Rule)
        // First run after upgrade: honour an access-control setup made in 高级.
        return if (ServiceStore(c).accessControlMode == AccessControlMode.AcceptSelected) ProxyMode.Apps
        else ProxyMode.Rule
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
     * Persists [mode] and pushes it to the service/core.
     * @return true when the VPN must be restarted for the change to take effect
     *         (access control is read only when the TUN interface is built).
     */
    suspend fun apply(c: Context, mode: ProxyMode): Boolean {
        val restart = withContext(Dispatchers.IO) {
            prefs(c).edit().putString(KEY_MODE, mode.name).commit()
            val srv = ServiceStore(c)
            val old = srv.accessControlMode
            val new = when {
                mode == ProxyMode.Apps -> AccessControlMode.AcceptSelected
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
