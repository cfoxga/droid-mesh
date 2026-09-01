package com.cfox.droidmesh.utils

import org.junit.Assert.*
import org.junit.Test

/**
 * [DM-UI-TEST-020] resolveSystemDeviceName prefers the OS setting the user actually customized
 * over the one still at its factory default, regardless of which field that is per-manufacturer.
 */
class CpuStatsHelperTest {

    // [DM-UI-TEST-020a] Meta Portal: device_name defaults to raw model, user name lives in
    // bluetooth_name with an OS-appended duplicate trailing word -- must resolve to the
    // deduped bluetooth_name, not the generic model string.
    @Test
    fun testPortalPrefersBluetoothNameOverModelDefaultedGlobalName() {
        val resolved = CpuStatsHelper.resolveSystemDeviceName(
            globalName = "PortalMini",
            bluetoothName = "Master Bedroom Portal Portal",
            buildModel = "PortalMini",
            buildManufacturer = "Facebook"
        )
        assertEquals("Master Bedroom Portal", resolved)
    }

    // [DM-UI-TEST-020b] Second Portal shape: no duplicate suffix present, name passes through untouched.
    @Test
    fun testPortalBluetoothNameWithoutDuplicateSuffixPassesThroughUnchanged() {
        val resolved = CpuStatsHelper.resolveSystemDeviceName(
            globalName = "Portal",
            bluetoothName = "Great Room Portal",
            buildModel = "Portal",
            buildManufacturer = "Facebook"
        )
        assertEquals("Great Room Portal", resolved)
    }

    // [DM-UI-TEST-020c] onn Google TV: user name lives in device_name, bluetooth_name stays at
    // the generic model default -- must resolve to device_name (historical behavior preserved).
    @Test
    fun testGoogleTvPrefersGlobalDeviceNameOverModelDefaultedBluetoothName() {
        val resolved = CpuStatsHelper.resolveSystemDeviceName(
            globalName = "Theater",
            bluetoothName = "GoogleTV0302",
            buildModel = "GoogleTV0302",
            buildManufacturer = "onn"
        )
        assertEquals("Theater", resolved)
    }

    // [DM-UI-TEST-020d] Both fields still at factory default (fresh, unnamed device) -- falls
    // back to device_name first per historical order, no crash, no dedupe misfire.
    @Test
    fun testBothFieldsAtFactoryDefaultFallsBackToGlobalName() {
        val resolved = CpuStatsHelper.resolveSystemDeviceName(
            globalName = "PortalMini",
            bluetoothName = "PortalMini",
            buildModel = "PortalMini",
            buildManufacturer = "Facebook"
        )
        assertEquals("PortalMini", resolved)
    }

    // [DM-UI-TEST-020e] Both settings blank/null -- falls all the way back to manufacturer+model,
    // since "PortalMini" doesn't already start with "Facebook".
    @Test
    fun testBothSettingsBlankFallsBackToManufacturerModel() {
        val resolved = CpuStatsHelper.resolveSystemDeviceName(
            globalName = null,
            bluetoothName = "",
            buildModel = "PortalMini",
            buildManufacturer = "Facebook"
        )
        assertEquals("Facebook PortalMini", resolved)
    }
}
