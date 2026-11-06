package com.github.kr328.clash

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Toast
import com.github.kr328.clash.zhifeiyun.AppPickerDesign
import com.github.kr328.clash.zhifeiyun.PickApp
import com.github.kr328.clash.zhifeiyun.ProxyMode
import com.github.kr328.clash.zhifeiyun.ProxyModeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** 选择应用: which apps go through the VPN in 应用 mode (CMFA access-control list). */
class AppPickerActivity : BaseActivity<AppPickerDesign>() {
    override suspend fun main() {
        val ctx = applicationContext
        val selected = withContext(Dispatchers.IO) {
            ProxyModeManager.selectedApps(ctx).toMutableSet()
        }
        val original = selected.toSet()

        val design = AppPickerDesign(this, selected, ProxyModeManager.showSystemApps(this))

        defer {
            design.close()
            val now = selected.toSet()
            withContext(Dispatchers.IO) { ProxyModeManager.saveSelectedApps(ctx, now) }
            if (now != original && clashRunning && ProxyModeManager.current(ctx) == ProxyMode.Apps) {
                Toast.makeText(ctx, R.string.zfy_reconnecting, Toast.LENGTH_SHORT).show()
                // Restart outside this activity's scope so closing the screen is instant.
                restartScope.launch { ProxyModeManager.restartVpn(ctx) }
            }
        }

        setContentDesign(design)

        design.setApps(withContext(Dispatchers.Default) { loadApps() })

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive {
                    when (it) {
                        AppPickerDesign.Request.Close -> finish()
                        AppPickerDesign.Request.ToggleSystem ->
                            ProxyModeManager.setShowSystemApps(this@AppPickerActivity, design.showSystem)
                    }
                }
            }
        }
    }

    private fun loadApps(): List<PickApp> {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = runCatching {
            pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toHashSet()
        }.getOrDefault(hashSetOf())

        @Suppress("DEPRECATION")
        val installed = runCatching { pm.getInstalledApplications(0) }
            .getOrDefault(emptyList())

        return installed
            .asSequence()
            .filter { it.packageName != packageName }
            .map { ai ->
                val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
                val updated = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                val label = runCatching { ai.loadLabel(pm).toString() }.getOrNull()
                    ?.trim()?.ifBlank { null } ?: ai.packageName
                PickApp(
                    pkg = ai.packageName,
                    label = label,
                    userApp = ai.packageName in launchable && (!system || updated),
                    info = ai,
                )
            }
            .toList()
    }

    companion object {
        private val restartScope = MainScope()
    }
}
