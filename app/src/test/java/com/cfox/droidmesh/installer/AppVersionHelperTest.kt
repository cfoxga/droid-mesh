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

        whenever(mockPm.getInstalledPackages(0)).thenReturn(listOf(ksPkg, sysPkg, fbPkg, dmPkg))

        val installedApps = AppVersionHelper.getUserInstalledApps(mockContext)

        assertEquals(2, installedApps.size)
        assertEquals("com.cfox.droidmesh", installedApps[0].packageName)
        assertEquals("DroidMesh", installedApps[0].appName)
        assertEquals("me.jxl.kiosk_satellite", installedApps[1].packageName)
        assertEquals("Kiosk Satellite", installedApps[1].appName)
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
}
