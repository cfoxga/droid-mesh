package com.cfox.kioskupdater.installer

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.cfox.kioskupdater.utils.Logger

object AppVersionHelper {
    const val TARGET_PACKAGE = "me.jxl.kiosk_satellite"

    data class InstalledInfo(
        val isInstalled: Boolean,
        val versionName: String?,
        val versionCode: Long?
    )

    fun getInstalledVersion(context: Context, packageName: String = TARGET_PACKAGE): InstalledInfo {
        return try {
            val pm = context.packageManager
            val packageInfo: PackageInfo = pm.getPackageInfo(packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val versionName = packageInfo.versionName
            InstalledInfo(
                isInstalled = true,
                versionName = versionName,
                versionCode = versionCode
            )
        } catch (e: PackageManager.NameNotFoundException) {
            InstalledInfo(
                isInstalled = false,
                versionName = null,
                versionCode = null
            )
        } catch (e: Exception) {
            Logger.e("Error querying package info for $packageName", e)
            InstalledInfo(
                isInstalled = false,
                versionName = null,
                versionCode = null
            )
        }
    }

    /**
     * Determines if latestTag is newer than installed version name.
     * Normalizes "v1.2.3" -> "1.2.3" and splits by '.' or build metadata.
     */
    fun isUpdateAvailable(installedVersionName: String?, latestTag: String): Boolean {
        if (installedVersionName.isNullOrBlank()) {
            // Not installed or unknown -> update is available
            return true
        }

        val cleanInstalled = installedVersionName.trim().removePrefix("v").substringBefore("-").substringBefore("+")
        val cleanLatest = latestTag.trim().removePrefix("v").substringBefore("-").substringBefore("+")

        if (cleanInstalled == cleanLatest) {
            return false
        }

        val installedParts = cleanInstalled.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(installedParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val vInst = installedParts.getOrElse(i) { 0 }
            val vLate = latestParts.getOrElse(i) { 0 }
            if (vLate > vInst) return true
            if (vLate < vInst) return false
        }

        // If semver prefix is identical, fallback to tag string inequality
        return latestTag.trim().removePrefix("v") != installedVersionName.trim().removePrefix("v")
    }
}
