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

    // [PROGRAMMATIC] PROV-TEST-012 (PROV-BEHAVE-011, gitea#89): Android 13+/14 resets the
    // ACCESS_RESTRICTED_SETTINGS app-op to `deny` for a sideloaded app on every install/update,
    // which silently strips the accessibility grant back out even when it had been written
    // correctly before. The manual externalCommand shown in the Repair Needed banner has to clear
    // that app-op before writing the accessibility settings, or an admin following it by hand hits
    // the exact same silent-revert loop the in-app repair used to.
    @Test
    fun testClassifyAccessibilityCommandClearsRestrictedSettingsFirst() {
        val notEnabled = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = false,
            accessibilityServiceRunning = false,
            batteryExemptionGranted = true
        ).items.first { it.key == ProvisioningAuditor.KEY_ACCESSIBILITY }

        assertTrue(
            "expected the restricted-settings app-op to be cleared before the accessibility " +
                "settings are written, got: ${notEnabled.externalCommand}",
            notEnabled.externalCommand.contains(
                "appops set com.cfox.droidmesh ACCESS_RESTRICTED_SETTINGS allow"
            )
        )
        val restrictedSettingsIndex = notEnabled.externalCommand.indexOf("ACCESS_RESTRICTED_SETTINGS")
        val enabledServicesIndex = notEnabled.externalCommand.indexOf("enabled_accessibility_services")
        assertTrue(
            "the restricted-settings clear must run before the settings write, not after -- " +
                "otherwise the OS can strip the write back out before the clear ever lands",
            restrictedSettingsIndex in 0 until enabledServicesIndex
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

    // PROV-TEST-010 (PROV-BEHAVE-009): the relative-dot shorthand ("pkg/.Class") an operator's adb
    // shell typically writes is what Android's own ComponentName parser and
    // AccessibilityManagerService treat as identical to the fully-qualified form -- the merge must
    // recognize that too, or it appends a redundant fully-qualified duplicate for a service that is
    // already correctly enabled under the shorthand spelling.
    @Test
    fun testMergeAccessibilityServices_recognizesRelativeDotFormAsAlreadyPresent() {
        val existing = "dev.vodik7.tvquickactions.free/dev.vodik7.tvquickactions.KeyAccessibilityService:" +
            "com.cfox.droidmesh/.service.AutoInstallService"
        val merged = ProvisioningAuditor.mergeAccessibilityServices(existing)
        assertEquals(
            "the relative-dot entry already satisfies the merge; nothing should be appended",
            existing,
            merged
        )
    }

    // PROV-TEST-010 (PROV-BEHAVE-009): same relative-dot recognition on the audit read path --
    // isAccessibilityGranted's underlying component match must not report "missing" for a service
    // that is actually bound and enabled, just spelled with the shorthand.
    @Test
    fun testComponentNamesEqual_recognizesRelativeDotForm() {
        assertTrue(
            "relative-dot shorthand must be recognized as the same component as the fully-qualified form",
            ProvisioningAuditor.componentNamesEqual(
                "com.cfox.droidmesh/.service.AutoInstallService",
                ProvisioningAuditor.ACCESSIBILITY_SERVICE_COMPONENT
            )
        )
    }

    @Test
    fun testComponentNamesEqual_rejectsDifferentComponents() {
        assertFalse(
            ProvisioningAuditor.componentNamesEqual(
                "com.cfox.droidmesh/.service.SomeOtherService",
                ProvisioningAuditor.ACCESSIBILITY_SERVICE_COMPONENT
            )
        )
    }

    // A same-simple-class-name-in-a-different-package mutant would pass every other test above
    // (they all vary either the package or the class, never hold the class fixed while swapping
    // the package) -- this pins the package as part of component identity, not just the class.
    @Test
    fun testComponentNamesEqual_rejectsSameClassNameInDifferentPackage() {
        assertFalse(
            ProvisioningAuditor.componentNamesEqual(
                "com.evil.impostor/.service.AutoInstallService",
                ProvisioningAuditor.ACCESSIBILITY_SERVICE_COMPONENT
            )
        )
    }

    // [PROGRAMMATIC] PROV-TEST-011: a failed repair has to say why, per item. The wording for a
    // pending on-screen ADB authorization is the whole point - it is the one repair failure a
    // person can act on, and it used to reach the operator as nothing but a 60s stall.
    @Test
    fun `PROV-TEST-011 repair failures name the item and an actionable reason`() {
        val battery = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = true,
            accessibilityServiceRunning = true,
            batteryExemptionGranted = false
        ).items.first { it.key == ProvisioningAuditor.KEY_BATTERY_OPTIMIZATION }

        val pending = ProvisioningAuditor.describeRepairFailure(
            battery,
            com.cfox.droidmesh.installer.AdbAuthorizationPendingException()
        )
        assertEquals(ProvisioningAuditor.KEY_BATTERY_OPTIMIZATION, pending.key)
        assertEquals("Battery Optimization Exemption", pending.label)
        assertTrue(
            "expected on-screen authorization instructions, got: ${pending.error}",
            pending.error.contains("Allow debugging from this computer?")
        )
        assertTrue(
            "expected the operator to be told a retry then works, got: ${pending.error}",
            pending.error.contains("run the repair again")
        )
        // PROV-OPEN-003: adbd only dispatches one authorization prompt at a time and never clears
        // that slot when a client gives up, so on a device that already saw a timed-out attempt no
        // dialog appears at all and only a reboot un-wedges it. Observed twice on the Theater GTV
        // while verifying this fix - the reason text has to say so or the operator waits forever
        // for a prompt that cannot come.
        assertTrue(
            "expected a way out when no prompt ever appears, got: ${pending.error}",
            pending.error.contains("restart the device")
        )

        // Any other failure keeps its own message rather than being dressed up as an auth prompt.
        val refused = ProvisioningAuditor.describeRepairFailure(
            battery,
            java.net.ConnectException("Connection refused")
        )
        assertEquals("Connection refused", refused.error)
        assertFalse(refused.error.contains("Allow debugging"))

        // A message-less exception still has to produce something legible, not "null".
        val bare = ProvisioningAuditor.describeRepairFailure(battery, java.net.SocketTimeoutException())
        assertEquals("SocketTimeoutException", bare.error)
        assertTrue(ProvisioningAuditor.describeRepairFailure(battery, null).error.isNotEmpty())
    }

    // [PROGRAMMATIC] PROV-TEST-013 (gitea#90): UpdaterForegroundService decides whether to warn
    // about PROV-BEHAVE-008's rebind-toggle side effect from the audit alone, before repair() has
    // run — this pins that decision to the exact "enabled but not running" sub-case and no other.
    @Test
    fun `PROV-TEST-013 accessibilityRebindToggleWillFire fires only for the enabled-but-not-running case`() {
        val rebindCase = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = true,
            accessibilityServiceRunning = false,
            batteryExemptionGranted = true
        )
        assertTrue(
            "expected the rebind-toggle warning for the enabled-but-not-running case",
            ProvisioningAuditor.accessibilityRebindToggleWillFire(rebindCase)
        )

        val notEnabledAtAllCase = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = false,
            accessibilityServiceRunning = false,
            batteryExemptionGranted = true
        )
        assertFalse(
            "the plain enable/merge path doesn't toggle anything off, so no warning is needed",
            ProvisioningAuditor.accessibilityRebindToggleWillFire(notEnabledAtAllCase)
        )

        val allSatisfiedCase = ProvisioningAuditor.classify(
            installPackagesGranted = true,
            accessibilityGranted = true,
            accessibilityServiceRunning = true,
            batteryExemptionGranted = true
        )
        assertFalse(
            "nothing to repair means no toggle can fire",
            ProvisioningAuditor.accessibilityRebindToggleWillFire(allSatisfiedCase)
        )
    }

    // [PROGRAMMATIC] PROV-TEST-013 (gitea#90): the auto-repair log line has to actually say what
    // happened — this is the only unit-testable part of the auto-repair path, since repair()
    // itself needs a live ADB session and Context that no unit test here provides.
    @Test
    fun `PROV-TEST-013 describeAutoRepairOutcome names what was repaired and what still fails`() {
        val onlyRepaired = ProvisioningAuditor.ProvisioningRepairResult(
            audit = ProvisioningAuditor.classify(true, true, true, true),
            repairedKeys = listOf(ProvisioningAuditor.KEY_ACCESSIBILITY),
            failures = emptyList()
        )
        val repairedText = ProvisioningAuditor.describeAutoRepairOutcome(onlyRepaired)
        assertTrue(repairedText.contains(ProvisioningAuditor.KEY_ACCESSIBILITY))
        assertFalse("nothing failed, so no failure wording should appear", repairedText.contains("fail"))

        val onlyFailed = ProvisioningAuditor.ProvisioningRepairResult(
            audit = ProvisioningAuditor.classify(true, true, true, true),
            repairedKeys = emptyList(),
            failures = listOf(
                ProvisioningAuditor.ProvisioningRepairFailure(
                    key = ProvisioningAuditor.KEY_BATTERY_OPTIMIZATION,
                    label = "Battery Optimization Exemption",
                    error = "ADB is not enabled"
                )
            )
        )
        val failedText = ProvisioningAuditor.describeAutoRepairOutcome(onlyFailed)
        assertTrue(failedText.contains("Battery Optimization Exemption"))
        assertTrue(failedText.contains("ADB is not enabled"))

        val nothingToDo = ProvisioningAuditor.ProvisioningRepairResult(
            audit = ProvisioningAuditor.classify(true, true, true, true),
            repairedKeys = emptyList(),
            failures = emptyList()
        )
        assertTrue(ProvisioningAuditor.describeAutoRepairOutcome(nothingToDo).isNotEmpty())
    }

    // [PROGRAMMATIC] PROV-TEST-014 (gitea#92): a device that already has ACCESS_RESTRICTED_SETTINGS
    // allowed (app-ops persist across updates, independent of this ADB key's trust state) must not
    // have its accessibility repair blocked just because the current process's loopback ADB key
    // happens to be unauthorized right now -- as long as WRITE_SECURE_SETTINGS is held, the actual
    // Settings.Secure writes don't need that ADB session at all. Only abort when neither path works.
    @Test
    fun `PROV-TEST-014 accessibility repair only aborts on a restricted-settings clear failure when no direct-write fallback exists`() {
        assertFalse(
            "WRITE_SECURE_SETTINGS held -> proceed via direct write despite the ADB clear failing",
            ProvisioningAuditor.shouldAbortAccessibilityRepair(
                restrictedSettingsClearSucceeded = false,
                canWriteSecureSettingsDirectly = true
            )
        )
        assertTrue(
            "no WRITE_SECURE_SETTINGS and the ADB clear failed -> nothing left to try, abort",
            ProvisioningAuditor.shouldAbortAccessibilityRepair(
                restrictedSettingsClearSucceeded = false,
                canWriteSecureSettingsDirectly = false
            )
        )
        assertFalse(
            "the ADB clear succeeding is always fine regardless of WRITE_SECURE_SETTINGS",
            ProvisioningAuditor.shouldAbortAccessibilityRepair(
                restrictedSettingsClearSucceeded = true,
                canWriteSecureSettingsDirectly = false
            )
        )
        assertFalse(
            ProvisioningAuditor.shouldAbortAccessibilityRepair(
                restrictedSettingsClearSucceeded = true,
                canWriteSecureSettingsDirectly = true
            )
        )
    }

    // [PROGRAMMATIC] PROV-TEST-015 (gitea#92): when the ADB clear fails but repair proceeds anyway
    // via WRITE_SECURE_SETTINGS, /api/logs needs to say why an "ADB error" didn't actually stop
    // the repair -- otherwise a failed ADB clear reads as an unexplained inconsistency next to a
    // successful outcome.
    @Test
    fun `PROV-TEST-015 describeRestrictedSettingsClearBypass names the ADB error it proceeded past`() {
        val text = ProvisioningAuditor.describeRestrictedSettingsClearBypass("SocketTimeoutException")
        assertTrue(text.contains("SocketTimeoutException"))
        assertTrue(text.contains("WRITE_SECURE_SETTINGS"))
    }
}
