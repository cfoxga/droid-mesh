package com.cfox.droidmesh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PROGRAMMATIC] PROV-TEST-001/002/003: pure-function coverage for the boot-time provisioning
 * audit's classifier and its accessibility-services merge logic. No Android framework calls —
 * see project/docs/SPEC/provisioning.md.
 */
class ProvisioningAuditorTest {

    @Test
    fun testClassifyAllSatisfied() {
        val result = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = true,
            batteryExemptionGranted = true
        )
        assertFalse("no repair needed when all three grants are present", result.repairNeeded)
        assertEquals(3, result.items.size)
        result.items.forEach {
            assertTrue("${it.key} should be satisfied", it.satisfied)
        }
    }

    @Test
    fun testClassifyEachItemIndependently() {
        val onlyInstallMissing = ProvisioningAuditor.classify(
            installPackagesGranted = false,
            accessibilityGranted = true,
            batteryExemptionGranted = true
        )
        assertTrue(onlyInstallMissing.repairNeeded)
        assertItemSatisfied(onlyInstallMissing, ProvisioningAuditor.KEY_INSTALL_PACKAGES, false)
        assertItemSatisfied(onlyInstallMissing, ProvisioningAuditor.KEY_ACCESSIBILITY, true)
        assertItemSatisfied(onlyInstallMissing, ProvisioningAuditor.KEY_BATTERY_OPTIMIZATION, true)

        val onlyAccessibilityMissing = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = false,
            batteryExemptionGranted = true
        )
        assertTrue(onlyAccessibilityMissing.repairNeeded)
        assertItemSatisfied(onlyAccessibilityMissing, ProvisioningAuditor.KEY_INSTALL_PACKAGES, true)
        assertItemSatisfied(onlyAccessibilityMissing, ProvisioningAuditor.KEY_ACCESSIBILITY, false)
        assertItemSatisfied(onlyAccessibilityMissing, ProvisioningAuditor.KEY_BATTERY_OPTIMIZATION, true)

        val onlyBatteryMissing = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = true,
            batteryExemptionGranted = false
        )
        assertTrue(onlyBatteryMissing.repairNeeded)
        assertItemSatisfied(onlyBatteryMissing, ProvisioningAuditor.KEY_INSTALL_PACKAGES, true)
        assertItemSatisfied(onlyBatteryMissing, ProvisioningAuditor.KEY_ACCESSIBILITY, true)
        assertItemSatisfied(onlyBatteryMissing, ProvisioningAuditor.KEY_BATTERY_OPTIMIZATION, false)
    }

    private fun assertItemSatisfied(
        result: ProvisioningAuditor.ProvisioningAuditResult,
        key: String,
        expected: Boolean
    ) {
        val item = result.items.first { it.key == key }
        assertEquals("$key.satisfied", expected, item.satisfied)
    }

    @Test
    fun testMergeAccessibilityServices_appendsWhenAbsent() {
        val existing = "com.facebook.alohaservices.presence/com.facebook.aloha.system.device"
        val merged = ProvisioningAuditor.mergeAccessibilityServices(existing)
        assertEquals(
            "$existing:${ProvisioningAuditor.ACCESSIBILITY_SERVICE_COMPONENT}",
            merged
        )
    }

    @Test
    fun testMergeAccessibilityServices_idempotentWhenAlreadyPresent() {
        val existing = "com.facebook.alohaservices.presence/com.facebook.aloha.system.device:" +
            ProvisioningAuditor.ACCESSIBILITY_SERVICE_COMPONENT
        val merged = ProvisioningAuditor.mergeAccessibilityServices(existing)
        assertEquals(existing, merged)
    }

    @Test
    fun testMergeAccessibilityServices_treatsNullAndLiteralNullAsEmpty() {
        assertEquals(ProvisioningAuditor.ACCESSIBILITY_SERVICE_COMPONENT, ProvisioningAuditor.mergeAccessibilityServices(null))
        assertEquals(ProvisioningAuditor.ACCESSIBILITY_SERVICE_COMPONENT, ProvisioningAuditor.mergeAccessibilityServices("null"))
        assertEquals(ProvisioningAuditor.ACCESSIBILITY_SERVICE_COMPONENT, ProvisioningAuditor.mergeAccessibilityServices(""))
    }
}
