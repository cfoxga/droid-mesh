package com.cfox.droidmesh.installer

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleTvUpdatePolicyTest {
    @Test
    fun televisionModeSuppressesInteractiveUpdateUiAndRelaunch() {
        val televisionMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES

        assertTrue(GoogleTvUpdatePolicy.suppressInteractiveUpdateUi(televisionMode))
        assertTrue(GoogleTvUpdatePolicy.suppressPostInstallLaunch(televisionMode))
    }

    @Test
    fun nonTelevisionModesRetainExistingUpdateBehavior() {
        val normalNightMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
        val deskMode = Configuration.UI_MODE_TYPE_DESK

        assertFalse(GoogleTvUpdatePolicy.suppressInteractiveUpdateUi(normalNightMode))
        assertFalse(GoogleTvUpdatePolicy.suppressPostInstallLaunch(normalNightMode))
        assertFalse(GoogleTvUpdatePolicy.suppressInteractiveUpdateUi(deskMode))
    }
}
