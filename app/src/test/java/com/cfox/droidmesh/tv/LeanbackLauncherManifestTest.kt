package com.cfox.droidmesh.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Parses the real, on-disk AndroidManifest.xml (not a hardcoded copy) and
 * asserts the declarations that make GTV home launchers (the stock one and
 * third-party ones such as Projectivy) file DroidMesh under their TV row
 * with a banner card instead of the mobile-app row with a square icon.
 * Kiosk Satellite hit and fixed the identical categorization bug
 * (kiosk-satellite commit af90767e) with the same three declarations
 * asserted here. This is a manifest-attribute assertion rather than a live
 * on-device launcher check because this module has no androidTest
 * instrumentation harness -- a live device is exercised separately as part
 * of deploy verification (gitea#81).
 */
class LeanbackLauncherManifestTest {

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

    private fun parsedManifest(): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(manifestFile()).documentElement
    }

    private fun findActivityElement(name: String): Element {
        val doc = parsedManifest()
        val activities = doc.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val el = activities.item(i) as Element
            if (el.getAttributeNS(android, "name") == name) return el
        }
        fail("No <activity> declaration found for android:name=\"$name\"")
        throw IllegalStateException("unreachable")
    }

    // GTV home launchers file an app under their TV row and draw its
    // banner only when its launcher Activity answers to
    // LEANBACK_LAUNCHER; without it the app files as a mobile app with a
    // square icon (gitea#81).
    @Test
    fun testMainActivityHasLeanbackLauncherCategory() {
        val activity = findActivityElement(".MainActivity")
        val intentFilters = activity.getElementsByTagName("intent-filter")
        var found = false
        for (i in 0 until intentFilters.length) {
            val filter = intentFilters.item(i) as Element
            val categories = filter.getElementsByTagName("category")
            for (j in 0 until categories.length) {
                val cat = categories.item(j) as Element
                if (cat.getAttributeNS(android, "name") == "android.intent.category.LEANBACK_LAUNCHER") {
                    found = true
                }
            }
        }
        assertTrue(
            "MainActivity must declare a second intent-filter with " +
                "category android.intent.category.LEANBACK_LAUNCHER, alongside " +
                "the existing LAUNCHER filter, or GTV home launchers file the " +
                "app as a mobile app instead of a TV app",
            found
        )
    }

    // GTV sticks have no touchscreen; the app is driven by d-pad there, so
    // a store/launcher must not filter or reclassify the install on this
    // implicit feature requirement (gitea#81).
    @Test
    fun testTouchscreenFeatureIsNotRequired() {
        val doc = parsedManifest()
        val usesFeature = doc.getElementsByTagName("uses-feature")
        var el: Element? = null
        for (i in 0 until usesFeature.length) {
            val e = usesFeature.item(i) as Element
            if (e.getAttributeNS(android, "name") == "android.hardware.touchscreen") el = e
        }
        assertNotNull(
            "Manifest must declare <uses-feature android:name=\"android.hardware.touchscreen\" " +
                "android:required=\"false\"/> so touchscreen-less GTV sticks aren't filtered out",
            el
        )
        assertEquals(
            "android.hardware.touchscreen must be android:required=\"false\"",
            "false",
            el!!.getAttributeNS(android, "required")
        )
    }

    // A LEANBACK_LAUNCHER entry with no banner falls back to a rasterized
    // launcher icon, but that's not what a TV home draws as its card --
    // the application must declare a real banner drawable (gitea#81).
    @Test
    fun testApplicationDeclaresTvBanner() {
        val doc = parsedManifest()
        val application = doc.getElementsByTagName("application").item(0) as Element
        val banner = application.getAttributeNS(android, "banner")
        assertEquals(
            "<application> must declare android:banner pointing at a TV banner drawable",
            "@drawable/tv_banner",
            banner
        )
    }
}
