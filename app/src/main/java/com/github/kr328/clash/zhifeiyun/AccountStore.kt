package com.github.kr328.clash.zhifeiyun

import android.content.Context
import java.util.UUID

class AccountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("zhifeiyun_account", Context.MODE_PRIVATE)

    var email: String?
        get() = prefs.getString("email", null)
        set(v) = prefs.edit().putString("email", v).apply()

    var subUrl: String?
        get() = prefs.getString("sub_url", null)
        set(v) = prefs.edit().putString("sub_url", v).apply()

    var profileUuid: UUID?
        get() = prefs.getString("profile_uuid", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        set(v) = prefs.edit().putString("profile_uuid", v?.toString()).apply()

    var cookies: Map<String, String>
        get() = prefs.getString("cookies", "")!!.split('\n').filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        set(v) = prefs.edit().putString("cookies", v.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()

    /** Values scraped from the /user page (fallback when subscription headers are missing). */
    var htmlRemaining: String?
        get() = prefs.getString("html_remaining", null)
        set(v) = prefs.edit().putString("html_remaining", v).apply()

    var htmlExpire: String?
        get() = prefs.getString("html_expire", null)
        set(v) = prefs.edit().putString("html_expire", v).apply()

    /** Whether the default auto (url-test) node has been applied for the current login. */
    var autoNodeApplied: Boolean
        get() = prefs.getBoolean("auto_node_applied", false)
        set(v) = prefs.edit().putBoolean("auto_node_applied", v).apply()

    val loggedIn: Boolean get() = !subUrl.isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }
}
