package com.cfox.droidmesh.installer

import android.content.Context
import android.content.res.Configuration

/** Keeps household Google TV playback free of installer and app-launch interruptions. */
object GoogleTvUpdatePolicy {
    fun suppressInteractiveUpdateUi(context: Context): Boolean =
        runCatching { suppressInteractiveUpdateUi(context.resources.configuration.uiMode) }.getOrDefault(false)

    fun suppressInteractiveUpdateUi(uiMode: Int): Boolean =
        (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    fun suppressPostInstallLaunch(context: Context): Boolean =
        runCatching { suppressPostInstallLaunch(context.resources.configuration.uiMode) }.getOrDefault(false)

    fun suppressPostInstallLaunch(uiMode: Int): Boolean = suppressInteractiveUpdateUi(uiMode)

    fun deferredReason(packageName: String): String =
        "Deferred $packageName: interactive updates are disabled on Google TV to preserve viewer focus"
}
