package com.cfox.droidmesh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PROGRAMMATIC] PROV-TEST-001/002/003/007/008: pure-function coverage for the boot-time
 * provisioning audit's classifier and its accessibility-services merge logic. No Android
 * framework calls — see project/docs/SPEC/provisioning.md.
 */
class ProvisioningAuditorTest {

    @Test
    fun testClassifyAllSatisfied() {
        val result = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = true,
            accessibilityServiceRunning = true,
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
            accessibilityServiceRunning = true,
            batteryExemptionGranted = true
        )
        assertTrue(onlyInstallMissing.repairNeeded)
        assertItemSatisfied(onlyInstallMissing, ProvisioningAuditor.KEY_INSTALL_PACKAGES, false)
        assertItemSatisfied(onlyInstallMissing, ProvisioningAuditor.KEY_ACCESSIBILITY, true)
        assertItemSatisfied(onlyInstallMissing, ProvisioningAuditor.KEY_BATTERY_OPTIMIZATION, true)

        val onlyAccessibilityMissing = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = false,
            accessibilityServiceRunning = false,
            batteryExemptionGranted = true
        )
        assertTrue(onlyAccessibilityMissing.repairNeeded)
        assertItemSatisfied(onlyAccessibilityMissing, ProvisioningAuditor.KEY_INSTALL_PACKAGES, true)
        assertItemSatisfied(onlyAccessibilityMissing, ProvisioningAuditor.KEY_ACCESSIBILITY, false)
        assertItemSatisfied(onlyAccessibilityMissing, ProvisioningAuditor.KEY_BATTERY_OPTIMIZATION, true)

        val onlyBatteryMissing = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = true,
            accessibilityServiceRunning = true,
            batteryExemptionGranted = false
        )
        assertTrue(onlyBatteryMissing.repairNeeded)
        assertItemSatisfied(onlyBatteryMissing, ProvisioningAuditor.KEY_INSTALL_PACKAGES, true)
        assertItemSatisfied(onlyBatteryMissing, ProvisioningAuditor.KEY_ACCESSIBILITY, true)
        assertItemSatisfied(onlyBatteryMissing, ProvisioningAuditor.KEY_BATTERY_OPTIMIZATION, false)
    }

    // PROV-TEST-007: the OS can still list DroidMesh's service as enabled while the live
    // instance is dead (crashed, or killed by Doze/App Standby on a TV device) and hasn't
    // rebound — that must NOT read as satisfied, or the repair banner shows all-clear while
    // install-confirmation dialogs go unhandled (see PROV-BEHAVE-008).
    @Test
    fun testClassifyAccessibilityEnabledButNotRunning() {
        val result = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = true,
            accessibilityServiceRunning = false,
            batteryExemptionGranted = true
        )
        assertTrue("a dead service instance must trigger repairNeeded even though the OS setting is intact", result.repairNeeded)
        assertItemSatisfied(result, ProvisioningAuditor.KEY_ACCESSIBILITY, false)
    }

    // PROV-TEST-009. Companion to the above: satisfied must require BOTH signals, not just the live flag —
    // a live-running check alone (dropping the OS-setting check) would also pass every other
    // test here, since accessibilityGranted=false/accessibilityServiceRunning=true can't happen
    // on a real device but isn't structurally prevented by the classifier's signature.
    @Test
    fun testClassifyAccessibilityRunningButNotGranted() {
        val result = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = false,
            accessibilityServiceRunning = true,
            batteryExemptionGranted = true
        )
        assertItemSatisfied(result, ProvisioningAuditor.KEY_ACCESSIBILITY, false)
    }

    // PROV-TEST-008: the two accessibility failure modes need different fixes — merging the
    // component into enabled_accessibility_services is a no-op when it's already there, so the
    // surfaced externalCommand must differ (rebind toggle vs. enable+merge) depending on which
    // sub-case applies.
    @Test
    fun testClassifyDistinguishesNotEnabledFromNotRunningCommand() {
        val notEnabled = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = false,
            accessibilityServiceRunning = false,
            batteryExemptionGranted = true
        )
        val enabledButNotRunning = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = true,
            accessibilityServiceRunning = false,
            batteryExemptionGranted = true
        )
        val notEnabledItem = notEnabled.items.first { it.key == ProvisioningAuditor.KEY_ACCESSIBILITY }
        val notRunningItem = enabledButNotRunning.items.first { it.key == ProvisioningAuditor.KEY_ACCESSIBILITY }

        assertFalse(notEnabledItem.satisfied)
        assertFalse(notRunningItem.satisfied)
        assertTrue(
            "an already-enabled-but-dead service needs a rebind command, not the enable/merge command",
            notRunningItem.externalCommand != notEnabledItem.externalCommand
        )
        assertTrue(
            "the not-running item's label should tell the admin the service isn't the problem, the process is",
            notRunningItem.label != notEnabledItem.label
        )
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
