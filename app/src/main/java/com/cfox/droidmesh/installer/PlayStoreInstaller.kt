package com.cfox.droidmesh.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
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
}
