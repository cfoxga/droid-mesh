package com.cfox.kiosksatelliteupdater.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.cfox.kiosksatelliteupdater.utils.Logger
import java.io.File

object PackageInstallerDispatcher {
    private const val FILE_PROVIDER_AUTHORITY = "com.cfox.kiosksatelliteupdater.fileprovider"

    /**
     * Creates an installation Intent with FileProvider URI and starts the system PackageInstaller.
     */
    fun dispatchInstall(context: Context, apkFile: File): Result<Boolean> {
        return try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                Logger.e("Cannot install: APK file does not exist or is empty (${apkFile.absolutePath})")
                return Result.failure(IllegalArgumentException("APK file invalid or empty"))
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                FILE_PROVIDER_AUTHORITY,
                apkFile
            )

            Logger.i("Dispatching install for URI: $apkUri (File: ${apkFile.name}, size: ${apkFile.length()} bytes)")

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }

            // Also grant explicit URI permission to potential installer packages
            val installerPackages = listOf(
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller"
            )

            for (pkg in installerPackages) {
                try {
                    context.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (ignored: Exception) {
                }
            }

            context.startActivity(installIntent)
            Logger.i("Package installer intent started successfully")
            Result.success(true)
        } catch (e: Exception) {
            Logger.e("Failed to dispatch package installation", e)
            Result.failure(e)
        }
    }

    /**
     * Dispatches an uninstallation request for the target package using system PackageInstaller.
     */
    fun dispatchUninstall(context: Context, packageName: String = AppVersionHelper.TARGET_PACKAGE): Result<Boolean> {
        return try {
            Logger.i("Dispatching uninstallation for $packageName")
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent("com.cfox.kiosksatelliteupdater.ACTION_UNINSTALL_STATUS"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
            val packageInstaller = context.packageManager.packageInstaller
            packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            Logger.i("PackageInstaller.uninstall dispatched successfully for $packageName")
            Result.success(true)
        } catch (e: Exception) {
            Logger.w("PackageInstaller.uninstall failed (${e.message}), falling back to Intent.ACTION_DELETE")
            try {
                val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(uninstallIntent)
                Result.success(true)
            } catch (ex: Exception) {
                Logger.e("Failed to dispatch uninstallation", ex)
                Result.failure(ex)
            }
        }
    }
}
