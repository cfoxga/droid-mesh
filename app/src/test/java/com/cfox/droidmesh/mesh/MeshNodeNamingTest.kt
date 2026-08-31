package com.cfox.droidmesh.mesh

import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.settings.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

/**
 * [DM-MESH-TEST-010] Node display name uses user-configured name over raw Build.MODEL
 *
 * Verifies that:
 * 1. PeerNode stores and serializes a separate displayName field
 * 2. fromBeaconJson round-trips displayName correctly
 * 3. Fallback is deviceModel when displayName is absent (effectiveName)
 * 4. Missing auto-install apps are correctly identified
 * 5. Installed app list sorts sideloaded first then alpha
 */
class MeshNodeNamingTest {

    // [DM-MESH-TEST-010a] displayName serializes into beacon JSON and round-trips back
    @Test
    fun testPeerNodeDisplayNameSerializesAndDeserializes() {
        val node = PeerNode(
            id = "abc123",
            ip = "192.168.40.250",
            deviceModel = "onn AFTMM",
            displayName = "Theater GoogleTV"
        )

        val json = node.toJson()
        assertEquals("Theater GoogleTV", json.optString("displayName"))

        val restored = PeerNode.fromBeaconJson(json, "192.168.40.250")
        assertNotNull(restored)
        assertEquals("Theater GoogleTV", restored!!.displayName)
        assertEquals("onn AFTMM", restored.deviceModel)
    }

    // [DM-MESH-TEST-010b] displayName falls back gracefully when absent from beacon
    @Test
    fun testPeerNodeDisplayNameEmptyWhenAbsentFromBeacon() {
        val json = JSONObject().apply {
            put("type", "ks_mesh_beacon")
            put("id", "abc123")
            put("ip", "192.168.40.250")
            put("port", 2325)
            put("deviceModel", "onn AFTMM")
            // no displayName key at all
        }

        val restored = PeerNode.fromBeaconJson(json, "192.168.40.250")
        assertNotNull(restored)
        assertTrue("displayName should be blank when not in beacon", restored!!.displayName.isBlank())
        assertEquals("onn AFTMM", restored.deviceModel)
    }

    // [DM-MESH-TEST-010c] effectiveName returns displayName when set, else deviceModel
    @Test
    fun testPeerNodeEffectiveNamePrefersDisplayName() {
        val withDisplay = PeerNode(
            id = "a",
            ip = "192.168.40.1",
            deviceModel = "onn AFTMM",
            displayName = "Theater GoogleTV"
        )
        assertEquals("Theater GoogleTV", withDisplay.effectiveName)

        val withoutDisplay = PeerNode(
            id = "b",
            ip = "192.168.40.2",
            deviceModel = "Meta Portal",
            displayName = ""
        )
        assertEquals("Meta Portal", withoutDisplay.effectiveName)
    }

    // [DM-MESH-TEST-010d] autoInstall detection: only managed+autoInstall apps not installed
    @Test
    fun testMissingAutoInstallAppsIdentification() {
        val library = mapOf(
            "gallery.immich.app" to SettingsStore.MeshAppConfig(
                packageName = "gallery.immich.app",
                appName = "Immich",
                managed = true,
                autoInstall = true,
                targetVersion = "latest",
                autoUpdate = false,
                isSideloaded = true
            ),
            "com.netflix.ninja" to SettingsStore.MeshAppConfig(
                packageName = "com.netflix.ninja",
                appName = "Netflix",
                managed = true,
                autoInstall = false,
                targetVersion = "latest",
                autoUpdate = false,
                isSideloaded = false
            ),
            "me.jxl.kiosk_satellite" to SettingsStore.MeshAppConfig(
                packageName = "me.jxl.kiosk_satellite",
                appName = "Kiosk Satellite",
                managed = true,
                autoInstall = true,
                targetVersion = "latest",
                autoUpdate = true,
                isSideloaded = true
            )
        )
        val installedPkgs = setOf("me.jxl.kiosk_satellite", "com.netflix.ninja")

        val missing = library.values.filter { cfg ->
            cfg.managed && cfg.autoInstall && !installedPkgs.contains(cfg.packageName)
        }

        assertEquals(1, missing.size)
        assertEquals("gallery.immich.app", missing[0].packageName)
    }

    // [DM-MESH-TEST-010e] Installed app list sorting: sideloaded first then alpha
    @Test
    fun testInstalledAppListSortingSideloadedFirstThenAlpha() {
        val apps = listOf(
            AppVersionHelper.InstalledAppInfo("com.netflix.ninja", "Netflix", "8.0.0", 100L),
            AppVersionHelper.InstalledAppInfo("gallery.immich.app", "Immich", "1.0.0", 50L),
            AppVersionHelper.InstalledAppInfo("me.jxl.kiosk_satellite", "Kiosk Satellite", "2026.8.107", 198L),
            AppVersionHelper.InstalledAppInfo("com.amazon.fire.tv", "Amazon Prime", "1.0", 30L)
        )

        val libraryMap = mapOf(
            "gallery.immich.app" to SettingsStore.MeshAppConfig(
                packageName = "gallery.immich.app", appName = "Immich",
                managed = true, autoInstall = true, targetVersion = "latest",
                autoUpdate = false, isSideloaded = true
            ),
            "me.jxl.kiosk_satellite" to SettingsStore.MeshAppConfig(
                packageName = "me.jxl.kiosk_satellite", appName = "Kiosk Satellite",
                managed = true, autoInstall = true, targetVersion = "latest",
                autoUpdate = true, isSideloaded = true
            ),
            "com.netflix.ninja" to SettingsStore.MeshAppConfig(
                packageName = "com.netflix.ninja", appName = "Netflix",
                managed = true, autoInstall = false, targetVersion = "latest",
                autoUpdate = false, isSideloaded = false
            ),
            "com.amazon.fire.tv" to SettingsStore.MeshAppConfig(
                packageName = "com.amazon.fire.tv", appName = "Amazon Prime",
                managed = true, autoInstall = false, targetVersion = "latest",
                autoUpdate = false, isSideloaded = false
            )
        )

        val sorted = apps.sortedWith(
            compareByDescending<AppVersionHelper.InstalledAppInfo> { libraryMap[it.packageName]?.isSideloaded == true }
                .thenBy { it.appName.lowercase() }
        )

        // Sideloaded first: Immich, Kiosk Satellite (alpha within sideloaded)
        assertEquals("gallery.immich.app", sorted[0].packageName)
        assertEquals("me.jxl.kiosk_satellite", sorted[1].packageName)
        // Store apps: Amazon Prime, Netflix (alpha within store)
        assertEquals("com.amazon.fire.tv", sorted[2].packageName)
        assertEquals("com.netflix.ninja", sorted[3].packageName)
    }
}
