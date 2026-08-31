package com.cfox.droidmesh.installer

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.cfox.droidmesh.utils.Logger

object AppVersionHelper {
    const val TARGET_PACKAGE = "me.jxl.kiosk_satellite"

    data class InstalledInfo(
        val isInstalled: Boolean,
        val versionName: String?,
        val versionCode: Long?
    )

    data class InstalledAppInfo(
        val packageName: String,
        val appName: String,
        val versionName: String?,
        val versionCode: Long?
    )

    fun isExcludedAppPackage(packageName: String, context: Context? = null): Boolean {
        return packageName == "com.cfox.droidmesh" ||
               packageName == "com.cfox.kiosksatelliteupdater" ||
               (context != null && packageName == context.packageName)
    }

    fun isSideloadedApp(packageName: String): Boolean {
        return packageName == TARGET_PACKAGE ||
               packageName == "com.cfox.droidmesh" ||
               packageName == "com.cfoxga.foxtvagent" ||
               packageName == "com.cfoxga.hamgoogletv" ||
               packageName == "com.cfoxga.mpttv" ||
               packageName.startsWith("com.cfox") ||
               packageName.startsWith("me.jxl")
    }

    fun isVersionMismatch(installedVersionName: String?, targetVersion: String): Boolean {
        if (targetVersion.equals("latest", ignoreCase = true)) {
            return false
        }
        if (installedVersionName.isNullOrBlank()) {
            return true
        }
        val cleanInstalled = installedVersionName.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
            .substringBefore(" ")
            .substringBefore("(")
            .trim()
        val cleanTarget = targetVersion.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
            .substringBefore(" ")
            .substringBefore("(")
            .trim()
        return cleanInstalled != cleanTarget
    }

    fun getUserInstalledApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager ?: return emptyList()
        val installedApps = mutableListOf<InstalledAppInfo>()

        try {
            val packages = pm.getInstalledPackages(0)
            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                               (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (isSystem) continue

                val pkgName = pkg.packageName
                if (isOemOrSystemPackage(pkgName)) continue
                if (isExcludedAppPackage(pkgName, context)) continue

                val appName = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    pkgName
                }

                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkg.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkg.versionCode.toLong()
                }

                installedApps.add(
                    InstalledAppInfo(
                        packageName = pkgName,
                        appName = if (appName.isBlank()) pkgName else appName,
                        versionName = pkg.versionName,
                        versionCode = versionCode
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e("Error querying installed applications", e)
        }

        return installedApps.sortedBy { it.appName.lowercase() }
    }

    fun isOemOrSystemPackage(packageName: String): Boolean {
        if (packageName == "android" ||
            packageName.startsWith("com.android.") ||
            packageName.startsWith("com.facebook.") ||
            packageName.startsWith("com.oculus.") ||
            packageName.startsWith("com.google.android.cts") ||
            packageName.startsWith("org.codeaurora.") ||
            packageName.startsWith("com.qti.")
        ) {
            return true
        }
        return false
    }

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

    /**
     * Extracts the versionCode from a downloaded APK file.
     */
    fun getApkVersionCode(context: Context, apkFile: java.io.File): Long? {
        return try {
            val packageInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Logger.e("Error reading APK archive info for ${apkFile.name}", e)
            null
        }
    }

    /**
     * Checks if target APK is a downgrade compared to the currently installed build.
     */
    fun isDowngrade(installedVersionCode: Long?, apkVersionCode: Long?): Boolean {
        if (installedVersionCode == null || apkVersionCode == null) return false
        return apkVersionCode < installedVersionCode
    }
}
