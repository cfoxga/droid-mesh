package com.cfox.droidmesh.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted user-facing configuration. Currently just the auto-update toggle;
 * grows here rather than as scattered SharedPreferences calls if more show up.
 */
object SettingsStore {
    private const val PREFS_NAME = "kiosk_satellite_updater_settings"
    private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"

    // On by default: installing only the updater APK (e.g. via
    // kiosk-satellite-portal-setup.sh) should still end up with Kiosk
    // Satellite itself installed, with no separate /update trigger needed.
    private const val DEFAULT_AUTO_UPDATE_ENABLED = true

    fun isAutoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPDATE_ENABLED, DEFAULT_AUTO_UPDATE_ENABLED)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
