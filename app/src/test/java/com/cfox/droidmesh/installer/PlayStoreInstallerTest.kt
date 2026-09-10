package com.cfox.droidmesh.installer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayStoreInstallerTest {

    // [PROGRAMMATIC] INST-TEST-023: Store installs must be directed to the exact Play Store,
    // never a generic browser/chooser URI that another app could intercept.
    @Test
    fun testMarketUriTargetsRequestedPackage() {
        assertEquals(
            "market://details?id=nl.giejay.android.tv.immich",
            PlayStoreInstaller.marketUri("nl.giejay.android.tv.immich")
        )
        assertEquals("com.android.vending", PlayStoreInstaller.PLAY_STORE_PACKAGE)
    }
}
