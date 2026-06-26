package com.example.galaxybridge

import android.content.Context
import android.content.SharedPreferences

class SyncState(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("galaxy_bridge_sync", Context.MODE_PRIVATE)

    var lastSyncEpochMillis: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    companion object {
        private const val KEY_LAST_SYNC = "last_sync_epoch_millis"
    }
}
