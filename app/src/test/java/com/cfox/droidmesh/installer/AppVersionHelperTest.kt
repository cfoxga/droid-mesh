package com.cfox.droidmesh.installer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.cfox.droidmesh.mesh.PeerNode
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AppVersionHelperTest {

    // [PROGRAMMATIC] APP-TEST-001: System & OEM package filtering
    // [PROGRAMMATIC] UPD-TEST-001: Package filtering and version extraction
    @Test
    fun testIsOemOrSystemPackage() {
        // Built-in OEM / System packages
        assertTrue(AppVersionHelper.isOemOrSystemPackage("android"))
        assertTrue(AppVersionHelper.isOemOrSystemPackage("com.android.settings"))
        assertTrue(AppVersionHelper.isOemOrSystemPackage("com.facebook.aloha.app.ttsservice"))
        assertTrue(AppVersionHelper.isOemOrSystemPackage("com.facebook.alohaapps.launcher"))
        assertTrue(AppVersionHelper.isOemOrSystemPackage("com.oculus.updater"))
        assertTrue(AppVersionHelper.isOemOrSystemPackage("com.google.android.cts.shim"))
        assertTrue(AppVersionHelper.isOemOrSystemPackage("com.qti.confuridialer"))
        assertTrue(AppVersionHelper.isOemOrSystemPackage("org.codeaurora.bluetooth"))

        // User installed packages
        assertFalse(AppVersionHelper.isOemOrSystemPackage("me.jxl.kiosk_satellite"))
        assertFalse(AppVersionHelper.isOemOrSystemPackage("com.cfox.droidmesh"))
        assertFalse(AppVersionHelper.isOemOrSystemPackage("com.netflix.ninja"))
        assertFalse(AppVersionHelper.isOemOrSystemPackage("com.disney.disneyplus"))
    }

    // [PROGRAMMATIC] APP-TEST-002: User installed apps filtering
    @Test
    fun testGetUserInstalledAppsFiltersSystemAndOem() {
        val mockPm: PackageManager = mock()
        val mockContext: Context = mock {
            whenever(it.packageManager).thenReturn(mockPm)
        }

        // 1. Kiosk Satellite (User app)
        val ksAppInfo = ApplicationInfo().apply { flags = 0 }
        val ksPkg = PackageInfo().apply {
            packageName = "me.jxl.kiosk_satellite"
            versionName = "2026.8.107"
            applicationInfo = ksAppInfo
        }
        whenever(mockPm.getApplicationLabel(ksAppInfo)).thenReturn("Kiosk Satellite")

        // 2. DroidMesh (User app)
        val dmAppInfo = ApplicationInfo().apply { flags = 0 }
        val dmPkg = PackageInfo().apply {
            packageName = "com.cfox.droidmesh"
            versionName = "1.0.0"
            applicationInfo = dmAppInfo
        }
        whenever(mockPm.getApplicationLabel(dmAppInfo)).thenReturn("DroidMesh")

        // 3. System App (flags = FLAG_SYSTEM)
        val sysAppInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
        val sysPkg = PackageInfo().apply {
            packageName = "com.custom.sysapp"
            versionName = "1.0"
            applicationInfo = sysAppInfo
        }

        // 4. Facebook OEM App on Portal (/data/app without FLAG_SYSTEM)
        val fbAppInfo = ApplicationInfo().apply { flags = 0 }
        val fbPkg = PackageInfo().apply {
            packageName = "com.facebook.aloha.app.ttsservice"
            versionName = "1159.0.0.2.59"
            applicationInfo = fbAppInfo
        }

        // 5. Netflix (User store app)
        val netflixAppInfo = ApplicationInfo().apply { flags = 0 }
        val netflixPkg = PackageInfo().apply {
            packageName = "com.netflix.ninja"
            versionName = "8.0.0"
            applicationInfo = netflixAppInfo
        }
        whenever(mockPm.getApplicationLabel(netflixAppInfo)).thenReturn("Netflix")

        whenever(mockPm.getInstalledPackages(0)).thenReturn(listOf(ksPkg, sysPkg, fbPkg, dmPkg, netflixPkg))

        val installedApps = AppVersionHelper.getUserInstalledApps(mockContext)

        // DroidMesh must be excluded from user installed app list
        assertEquals(2, installedApps.size)
        assertEquals("me.jxl.kiosk_satellite", installedApps[0].packageName)
        assertEquals("Kiosk Satellite", installedApps[0].appName)
        assertEquals("com.netflix.ninja", installedApps[1].packageName)
        assertEquals("Netflix", installedApps[1].appName)
        assertFalse(installedApps.any { it.packageName == "com.cfox.droidmesh" })
    }

    // [PROGRAMMATIC] APP-TEST-005: DroidMesh exclusion from app lists
    @Test
    fun testIsExcludedAppPackage() {
        assertTrue(AppVersionHelper.isExcludedAppPackage("com.cfox.droidmesh"))
        assertTrue(AppVersionHelper.isExcludedAppPackage("com.cfox.kiosksatelliteupdater"))
        assertFalse(AppVersionHelper.isExcludedAppPackage("me.jxl.kiosk_satellite"))
        assertFalse(AppVersionHelper.isExcludedAppPackage("com.netflix.ninja"))
    }

    // [PROGRAMMATIC] MESH-TEST-001: PeerNode beacon serialization and deserialization
    @Test
    fun testPeerNodeSerializationWithInstalledApps() {
        val apps = listOf(
            AppVersionHelper.InstalledAppInfo("me.jxl.kiosk_satellite", "Kiosk Satellite", "2026.8.107", 198L),
            AppVersionHelper.InstalledAppInfo("com.cfox.droidmesh", "DroidMesh", "1.0.0", 1L)
        )

        val node = PeerNode(
            id = "test-node",
            ip = "192.168.40.250",
            port = 2325,
            deviceModel = "PortalMini",
            installedApps = apps
        )

        val json = node.toJson()
        val appsJson = json.getJSONArray("installedApps")
        assertEquals(2, appsJson.length())
        assertEquals("Kiosk Satellite", appsJson.getJSONObject(0).getString("appName"))
        assertEquals("DroidMesh", appsJson.getJSONObject(1).getString("appName"))

        val deserialized = PeerNode.fromBeaconJson(json, "192.168.40.250")
        assertNotNull(deserialized)
        assertEquals(2, deserialized!!.installedApps.size)
        assertEquals("me.jxl.kiosk_satellite", deserialized.installedApps[0].packageName)
        assertEquals("2026.8.107", deserialized.installedApps[0].versionName)
        assertEquals("com.cfox.droidmesh", deserialized.installedApps[1].packageName)
    }

    // [PROGRAMMATIC] APP-TEST-004: Version mismatch and sideloaded app identification
    @Test
    fun testVersionMismatchAndSideloadedCheck() {
        assertTrue(AppVersionHelper.isSideloadedApp("me.jxl.kiosk_satellite"))
        assertTrue(AppVersionHelper.isSideloadedApp("com.cfox.droidmesh"))
        assertFalse(AppVersionHelper.isSideloadedApp("com.disney.disneyplus"))

        assertFalse(AppVersionHelper.isVersionMismatch("2026.8.107", "latest"))
        assertFalse(AppVersionHelper.isVersionMismatch("2026.8.107", "2026.8.107"))
        assertFalse(AppVersionHelper.isVersionMismatch("v2026.8.107", "2026.8.107"))
        assertFalse(AppVersionHelper.isVersionMismatch("0.0.1 (7)", "0.0.1"))
        assertFalse(AppVersionHelper.isVersionMismatch("0.0.1 (7)", "v0.0.1"))
        assertFalse(AppVersionHelper.isVersionMismatch("v0.0.1 (7)", "0.0.1"))
        assertFalse(AppVersionHelper.isVersionMismatch("0.0.1 (7)", "V0.0.1"))
        assertTrue(AppVersionHelper.isVersionMismatch("0.0.1 (7)", "0.0.2"))
        assertTrue(AppVersionHelper.isVersionMismatch("2026.8.106", "2026.8.107"))
        assertTrue(AppVersionHelper.isVersionMismatch(null, "2026.8.107"))
    }

    // [PROGRAMMATIC] UPD-TEST-001 / UPD-TEST-004: Self-update semver comparison against
    // DroidMesh's own build-number-suffixed versionName ("0.1.0 (150)")
    @Test
    fun testIsUpdateAvailable() {
        // Newer release published -> update available
        assertTrue(AppVersionHelper.isUpdateAvailable("0.0.1 (120)", "v0.1.0"))
        // Already on the latest release, build-number suffix must not cause a false positive
        assertFalse(AppVersionHelper.isUpdateAvailable("0.1.0 (150)", "v0.1.0"))
        // Plain (non-suffixed) versions, exact match
        assertFalse(AppVersionHelper.isUpdateAvailable("2026.8.107", "2026.8.107"))
        // Installed is newer than the fetched tag (no downgrade offer)
        assertFalse(AppVersionHelper.isUpdateAvailable("0.2.0 (200)", "v0.1.0"))
        // Not installed / unknown version -> treat as update available
        assertTrue(AppVersionHelper.isUpdateAvailable(null, "v0.1.0"))
    }
}
