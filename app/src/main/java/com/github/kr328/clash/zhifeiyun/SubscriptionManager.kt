package com.github.kr328.clash.zhifeiyun

import android.content.Context
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Imports / refreshes the single panel subscription through CMFA's own profile manager. */
object SubscriptionManager {
    suspend fun importSubscription(context: Context, url: String): Profile? {
        val store = AccountStore(context)
        return withProfile {
            // Keep exactly one profile: remove everything that is not ours.
            val keep = store.profileUuid
            queryAll().filter { it.uuid != keep }.forEach {
                runCatching { delete(it.uuid) }
            }

            val existing = keep?.let { queryByUUID(it) }
            val uuid = if (existing != null) {
                existing.uuid
            } else {
                create(Profile.Type.Url, PanelConfig.PROFILE_NAME, url)
            }
            store.profileUuid = uuid

            patch(uuid, PanelConfig.PROFILE_NAME, url, PanelConfig.UPDATE_INTERVAL_MS, null)
            commit(uuid)

            val profile = queryByUUID(uuid)
            if (profile != null) setActive(profile)
            queryByUUID(uuid)
        }
    }

    /** Triggers a background update of the subscription (traffic/expiry come with it). */
    suspend fun refresh(context: Context) {
        val uuid = AccountStore(context).profileUuid ?: return
        runCatching { withProfile { update(uuid) } }
    }

    suspend fun queryProfile(context: Context): Profile? {
        val uuid = AccountStore(context).profileUuid ?: return null
        return runCatching { withProfile { queryByUUID(uuid) } }.getOrNull()
    }

    suspend fun logout(context: Context) {
        val store = AccountStore(context)
        val uuid = store.profileUuid
        if (uuid != null) {
            runCatching { withProfile { delete(uuid) } }
        }
        withContext(Dispatchers.IO) { store.clear() }
    }
}
