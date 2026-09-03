package com.cfox.droidmesh.security

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * [PROGRAMMATIC] API-TEST-048 / INST-TEST-014: parses the real, on-disk
 * AndroidManifest.xml (not a hardcoded copy) and asserts the exported
 * surface of UpdaterForegroundService and AutoInstallService, closing
 * gitea#39. This is a manifest-attribute assertion rather than a live
 * cross-app bind attempt because this module has no androidTest
 * instrumentation harness -- a live device is exercised separately as
 * part of deploy verification.
 */
class ExportedServiceSurfaceTest {

    private val android = "http://schemas.android.com/apk/res/android"

    // Resolve relative to the module dir (Gradle's default unit-test
    // working directory) or, if run from elsewhere, walk up to find it --
    // never hardcode a copy of manifest content in the test itself.
    private fun manifestFile(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("droid-mesh/app/src/main/AndroidManifest.xml")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("Could not locate AndroidManifest.xml from working dir ${File(".").absolutePath}")
    }

    private fun findServiceElement(name: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val doc = factory.newDocumentBuilder().parse(manifestFile())
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val el = services.item(i) as Element
            if (el.getAttributeNS(android, "name") == name) return el
        }
        fail("No <service> declaration found for android:name=\"$name\"")
        throw IllegalStateException("unreachable")
    }

    // [PROGRAMMATIC] API-TEST-048: UpdaterForegroundService must not be
    // exported -- nothing outside this app has a documented need to start
    // it via explicit Intent (API-BEHAVE-031, gitea#39).
    @Test
    fun testUpdaterForegroundServiceIsNotExported() {
        val el = findServiceElement(".service.UpdaterForegroundService")
        assertEquals(
            "UpdaterForegroundService must declare android:exported=\"false\"",
            "false",
            el.getAttributeNS(android, "exported")
        )
    }

    // [PROGRAMMATIC] INST-TEST-014: AutoInstallService is an
    // AccessibilityService, which the OS requires to remain exported so
    // AccessibilityManagerService can bind to it -- but it must keep the
    // signature-level android:permission attribute that already restricts
    // every caller (start and bind) to holders of
    // android.permission.BIND_ACCESSIBILITY_SERVICE, a permission no
    // third-party app can hold (INST-BEHAVE-012, gitea#39).
    @Test
    fun testAutoInstallServiceRequiresAccessibilityBindPermission() {
        val el = findServiceElement(".service.AutoInstallService")
        assertEquals(
            "AutoInstallService must require android.permission.BIND_ACCESSIBILITY_SERVICE " +
                "to bind or start (the only mitigation available since it must stay exported " +
                "for system accessibility binding)",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            el.getAttributeNS(android, "permission")
        )
    }
}
