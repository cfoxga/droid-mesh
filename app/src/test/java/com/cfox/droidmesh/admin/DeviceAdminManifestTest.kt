package com.cfox.droidmesh.admin

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * [PROGRAMMATIC] INST-TEST-040: parses the real, on-disk AndroidManifest.xml (not a hardcoded
 * copy) and asserts DroidMeshDeviceAdminReceiver (INST-BEHAVE-023) is declared the way the OS
 * requires a device admin receiver to be declared before `dpm set-device-owner` will accept it:
 * bound behind the signature-level `BIND_DEVICE_ADMIN` permission, answering
 * `ACTION_DEVICE_ADMIN_ENABLED`, and pointing its `android.app.device_admin` meta-data at a real,
 * parseable `res/xml/device_admin.xml`. This is a manifest-attribute assertion rather than a live
 * `dpm set-device-owner` provisioning run because this module has no androidTest instrumentation
 * harness -- a live device is exercised separately as part of deploy verification.
 */
class DeviceAdminManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    // Resolve relative to the module dir (Gradle's default unit-test working directory) or, if
    // run from elsewhere, walk up to find it -- never hardcode a copy of manifest content here.
    private fun manifestFile(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("droid-mesh/app/src/main/AndroidManifest.xml")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("Could not locate AndroidManifest.xml from working dir ${File(".").absolutePath}")
    }

    private fun resourceFile(relativeToModule: String): File {
        val candidates = listOf(
            File("src/main/$relativeToModule"),
            File("app/src/main/$relativeToModule"),
            File("droid-mesh/app/src/main/$relativeToModule")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("Could not locate $relativeToModule from working dir ${File(".").absolutePath}")
    }

    private fun findReceiverElement(name: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val doc = factory.newDocumentBuilder().parse(manifestFile())
        val receivers = doc.getElementsByTagName("receiver")
        for (i in 0 until receivers.length) {
            val el = receivers.item(i) as Element
            if (el.getAttributeNS(android, "name") == name) return el
        }
        fail("No <receiver> declaration found for android:name=\"$name\"")
        throw IllegalStateException("unreachable")
    }

    // INST-TEST-040: the receiver requires BIND_DEVICE_ADMIN, answers
    // ACTION_DEVICE_ADMIN_ENABLED, and points at a real, parseable device_admin.xml -- the exact
    // shape `dpm set-device-owner` requires to accept this app as Device Owner.
    @Test
    fun testDeviceAdminReceiverDeclaredWithBindPermissionActionAndPolicyResource() {
        val el = findReceiverElement(".admin.DroidMeshDeviceAdminReceiver")

        assertEquals(
            "DroidMeshDeviceAdminReceiver must require android.permission.BIND_DEVICE_ADMIN",
            "android.permission.BIND_DEVICE_ADMIN",
            el.getAttributeNS(android, "permission")
        )

        val intentFilters = el.getElementsByTagName("intent-filter")
        assertTrue("receiver must declare at least one intent-filter", intentFilters.length > 0)
        var hasDeviceAdminEnabledAction = false
        for (i in 0 until intentFilters.length) {
            val filter = intentFilters.item(i) as Element
            val actions = filter.getElementsByTagName("action")
            for (j in 0 until actions.length) {
                val action = actions.item(j) as Element
                if (action.getAttributeNS(android, "name") == "android.app.action.DEVICE_ADMIN_ENABLED") {
                    hasDeviceAdminEnabledAction = true
                }
            }
        }
        assertTrue(
            "receiver must answer android.app.action.DEVICE_ADMIN_ENABLED",
            hasDeviceAdminEnabledAction
        )

        val metaDataList = el.getElementsByTagName("meta-data")
        var policyResource: String? = null
        for (i in 0 until metaDataList.length) {
            val meta = metaDataList.item(i) as Element
            if (meta.getAttributeNS(android, "name") == "android.app.device_admin") {
                policyResource = meta.getAttributeNS(android, "resource")
            }
        }
        assertEquals(
            "receiver must reference @xml/device_admin as its android.app.device_admin meta-data",
            "@xml/device_admin",
            policyResource
        )

        // The referenced resource must be a real, parseable <device-admin> file, not just a
        // manifest reference to a name that doesn't exist on disk.
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val policyDoc = factory.newDocumentBuilder().parse(resourceFile("res/xml/device_admin.xml"))
        assertEquals(
            "device_admin.xml root element must be <device-admin>",
            "device-admin",
            policyDoc.documentElement.tagName
        )
    }
}
