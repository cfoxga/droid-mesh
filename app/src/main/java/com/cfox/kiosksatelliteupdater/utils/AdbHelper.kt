package com.cfox.kiosksatelliteupdater.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object AdbHelper {

    fun isAdbEnabled(context: Context): Boolean {
        return try {
            val setting = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            )
            setting == 1
        } catch (e: Exception) {
            false
        }
    }

    suspend fun isAdbPortListening(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 5555), 400)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun toggleAdb(context: Context): Boolean {
        val current = isAdbEnabled(context)
        val newTarget = if (current) 0 else 1
        try {
            val success = Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                newTarget
            )
            if (success) {
                Logger.i("Toggled ADB_ENABLED to $newTarget")
                return true
            }
        } catch (e: Exception) {
            Logger.w("Cannot directly write ADB_ENABLED without system permissions: ${e.message}")
        }

        // Launch Developer Options as fallback
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Logger.i("Opened Developer Options to toggle ADB")
            return true
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return true
            } catch (ex: Exception) {
                Logger.e("Failed to open development settings", ex)
            }
        }
        return false
    }
}
