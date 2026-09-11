package com.cfox.droidmesh.mesh

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PROGRAMMATIC] ASET-TEST-013: a peer's per-app-settings health has to survive the beacon
 * round-trip, and a peer still running an older build (no such fields) must decode to a clean
 * "nothing wrong reported" rather than a crash or a false alarm.
 * See project/docs/SPEC/app-settings.md.
 */
class PeerNodeAppSettingsTest {

    // [PROGRAMMATIC] ASET-TEST-013
    @Test
    fun testAppSettingsHealthRoundTripsThroughBeaconJson() {
        val node = PeerNode(
            id = "gtv-theater",
            ip = "192.168.50.64",
            deviceModel = "onn 4K Pro",
            appSettingsMissing = 2,
            appSettingsUnverified = 1,
            appSettingsIssues = listOf(
                "TV Quick Actions: Accessibility service",
                "Kiosk Satellite: Battery optimization exemption"
            )
        )
        val json = node.toJson()
        assertEquals(2, json.getInt("appSettingsMissing"))
        assertEquals(1, json.getInt("appSettingsUnverified"))
        assertEquals(2, json.getJSONArray("appSettingsIssues").length())
        assertEquals("TV Quick Actions: Accessibility service", json.getJSONArray("appSettingsIssues").getString(0))

        val decoded = PeerNode.fromBeaconJson(json, "192.168.50.64")
        assertEquals(2, decoded?.appSettingsMissing)
        assertEquals(1, decoded?.appSettingsUnverified)
        assertEquals(node.appSettingsIssues, decoded?.appSettingsIssues)
    }

    // [PROGRAMMATIC] ASET-TEST-013 (negative): older peer, no fields on the wire.
    @Test
    fun testLegacyBeaconWithoutAppSettingsFieldsDecodesToDefaults() {
        val legacy = JSONObject()
            .put("id", "portal-basement")
            .put("ip", "192.168.40.59")
            .put("deviceModel", "Portal")
            .put("installedApps", JSONArray())
        val decoded = PeerNode.fromBeaconJson(legacy, "192.168.40.59")
        assertEquals(0, decoded?.appSettingsMissing)
        assertEquals(0, decoded?.appSettingsUnverified)
        assertTrue(decoded?.appSettingsIssues?.isEmpty() == true)
    }
}
