package com.cfox.droidmesh.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cfox.droidmesh.service.AutoInstallService
import com.cfox.droidmesh.utils.Logger

/** Launches a package's detail page in the installed Google Play Store. */
object PlayStoreInstaller {
    const val PLAY_STORE_PACKAGE = "com.android.vending"

    internal fun marketUri(packageName: String): String = "market://details?id=$packageName"

    fun dispatchInstall(context: Context, packageName: String): Result<Boolean> = runCatching {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            "Invalid Android package name: $packageName"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(marketUri(packageName))).apply {
            setPackage(PLAY_STORE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        require(intent.resolveActivity(context.packageManager) != null) {
            "Google Play Store is not available"
        }
        context.startActivity(intent)
        Logger.i("Opened Play Store detail page for $packageName")
        true
    }.onFailure { Logger.w("Could not open Play Store for $packageName: ${it.message}") }

    /** Outcome of a request to install a Store-origin package via [beginAndDispatch]. */
    sealed class DispatchOutcome {
        object Opened : DispatchOutcome()
        data class Skipped(val reason: String) : DispatchOutcome()
    }

    /**
     * [API-BEHAVE-042] Single choke point for arming and firing a Play Store install request --
     * shared by the hourly mesh auto-action loop (`FLT-BEHAVE-011`) and a manual `/update` trigger,
     * so the two never drift on what "eligible to install via Play Store" means. Requires
     * `AutoInstallService` to currently be bound (its accessibility click handling is what
     * completes the install once the Store dialog appears) and no other Play Store install already
     * pending -- both report as a named [DispatchOutcome.Skipped] rather than throwing, since a
     * dead accessibility service or an in-flight request are expected, recoverable states, not bugs.
     */
    fun beginAndDispatch(context: Context, packageName: String, appName: String): DispatchOutcome {
        if (!AutoInstallService.isServiceRunning) {
            return DispatchOutcome.Skipped("Accessibility service is disabled")
        }
        if (!AutoInstallService.beginPlayStoreInstall(packageName, appName)) {
            // There is exactly one pending-install slot system-wide (AutoInstallService.kt), so a
            // failed begin() means some OTHER package is holding it, not this one -- name that
            // package rather than the just-requested one, or the message reads backwards (e.g.
            // "install already pending for com.appB" when com.appA is actually the blocker).
            val holder = AutoInstallService.pendingPlayStoreInstall?.packageName
            return DispatchOutcome.Skipped(
                if (holder != null && holder != packageName) {
                    "Play Store install already pending for $holder; deferring $packageName"
                } else {
                    "Play Store install already pending for $packageName"
                }
            )
        }
        val dispatch = dispatchInstall(context, packageName)
        return if (dispatch.isSuccess) {
            DispatchOutcome.Opened
        } else {
            AutoInstallService.clearPendingPlayStoreInstall(packageName)
            DispatchOutcome.Skipped(dispatch.exceptionOrNull()?.message ?: "could not open Play Store")
        }
    }
}
