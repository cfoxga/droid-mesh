package com.cfox.droidmesh.utils

import com.cfox.droidmesh.installer.AdbLoopbackInstaller
import com.cfox.droidmesh.settings.AppSettingRequirement
import com.cfox.droidmesh.settings.AppSettingRequirement.SettingType
import com.cfox.droidmesh.settings.SettingsStore
import com.cfox.droidmesh.utils.AppSettingsAuditor.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PROGRAMMATIC] ASET-TEST-003..009, ASET-TEST-013, ASET-TEST-015, ASET-TEST-016: pure-function coverage for the per-app
 * required settings audit — no Android framework calls. Snapshots mirror real fleet state read
 * 2026-09-11. See project/docs/SPEC/app-settings.md.
 */
class AppSettingsAuditorTest {

    private val tqa = "dev.vodik7.tvquickactions.free"
    private val tqaA11y = "$tqa/dev.vodik7.tvquickactions.KeyAccessibilityService"
    private val projectivy = "com.spocky.projengmenu"
    private val kiosk = "me.jxl.kiosk_satellite"
    private val droidMeshA11y = "com.cfox.droidmesh/com.cfox.droidmesh.service.AutoInstallService"

    private fun req(pkg: String, type: SettingType, value: String = ""): AppSettingRequirement =
        requireNotNull(AppSettingRequirement.create(pkg, type, value)) { "fixture requirement invalid: $type $value" }

    private fun entry(pkg: String, name: String, vararg reqs: AppSettingRequirement) =
        SettingsStore.MeshAppConfig(packageName = pkg, appName = name, requiredSettings = reqs.toList())

    private fun statusOf(result: AppSettingsAuditor.AppSettingsAuditResult, type: SettingType): ItemStatus =
        result.items.single { it.requirement.type == type }.status

    // [PROGRAMMATIC] ASET-TEST-016 (negative) — command assembly re-asserts package ownership, so a
    // mismatched (packageName, requirement) pair can never produce a cross-package command even
    // though create() would have rejected it at construction time.
    @Test
    fun testRepairCommandsRejectsARequirementBelongingToAnotherPackage() {
        // create() cannot build one of these, so construct it directly to stand in for a future
        // refactor pairing one entry's packageName with a different entry's requirement.
        val droidMeshRequirement = AppSettingRequirement(SettingType.ACCESSIBILITY_SERVICE, droidMeshA11y)

        // Control: paired with its own package the very same requirement still assembles, which
        // proves the assertions below fail for the ownership mismatch and not for some other reason.
        val owned = AppSettingsAuditor.repairCommands("com.cfox.droidmesh", droidMeshRequirement, Result.success(""))
        assertTrue("own-package pairing must still assemble", owned.isSuccess)

        val crossPackage = AppSettingsAuditor.repairCommands(tqa, droidMeshRequirement, Result.success(""))
        assertTrue("a cross-package component must not assemble", crossPackage.isFailure)
        assertFalse(
            "no emitted command may name another package's component",
            crossPackage.getOrNull().orEmpty().any { it.contains(droidMeshA11y) }
        )

        // pm set-home-activity interpolates the component too, so it needs the same guard.
        val foreignHome = AppSettingRequirement(SettingType.DEFAULT_HOME, "$projectivy/$projectivy.HomeActivity")
        assertTrue(AppSettingsAuditor.repairCommands(tqa, foreignHome, null).isFailure)

        // A requirement that does belong to the package is unaffected.
        assertTrue(AppSettingsAuditor.repairCommands(tqa, req(tqa, SettingType.BATTERY_OPTIMIZATION), null).isSuccess)
    }

    // [PROGRAMMATIC] ASET-TEST-015 (negative) — a failed read is not an empty one. Merging against
    // a failed read would emit a put naming only the new service and disable every other one,
    // DroidMesh's own AutoInstallService included, which is precisely the outage this feature exists
    // to repair.
    @Test
    fun testRepairCommandsFailsRatherThanWipingAccessibilityOnFailedRead() {
        val requirement = req(tqa, SettingType.ACCESSIBILITY_SERVICE, tqaA11y)

        // Control: with a successful read the merge happens and both services survive, which proves
        // the assertions below could have failed if a failed read were treated the same way.
        val merged = AppSettingsAuditor.repairCommands(tqa, requirement, Result.success(droidMeshA11y))
            .getOrThrow()
        val put = merged.single { it.startsWith("settings put secure enabled_accessibility_services") }
        assertTrue("must keep the already-enabled service: $put", put.contains(droidMeshA11y))
        assertTrue("must add the required service: $put", put.contains(tqaA11y))

        val failedRead = AppSettingsAuditor.repairCommands(
            tqa, requirement, Result.failure(RuntimeException("adb session closed"))
        )
        assertTrue("a failed read must not produce repair commands", failedRead.isFailure)
        assertFalse(
            "a failed read must never reach the accessibility merge",
            failedRead.getOrNull().orEmpty().any { it.contains("enabled_accessibility_services") }
        )

        // An accessibility item with no read attempted at all is equally a failure, not an empty merge.
        assertTrue(AppSettingsAuditor.repairCommands(tqa, requirement, null).isFailure)

        // Types that need no read still plan normally.
        assertEquals(
            listOf("dumpsys deviceidle whitelist +$tqa"),
            AppSettingsAuditor.repairCommands(tqa, req(tqa, SettingType.BATTERY_OPTIMIZATION), null).getOrThrow()
        )
    }

    // [PROGRAMMATIC] ASET-TEST-003 (negative)
    @Test
    fun testEvaluateSkipsEntriesWhosePackageIsNotInstalled() {
        val library = listOf(
            entry(tqa, "TV Quick Actions", req(tqa, SettingType.ACCESSIBILITY_SERVICE, tqaA11y)),
            entry(kiosk, "Kiosk Satellite", req(kiosk, SettingType.BATTERY_OPTIMIZATION))
        )
        val result = AppSettingsAuditor.evaluate(library, setOf(kiosk), AppSettingsAuditor.DeviceSettingsSnapshot())
        assertEquals(1, result.items.size)
        assertEquals(kiosk, result.items[0].packageName)
        assertEquals("Kiosk Satellite", result.items[0].appName)
        assertEquals(ItemStatus.MISSING, result.items[0].status)
        assertTrue(result.repairNeeded)
        assertEquals(1, result.missingCount)

        val none = AppSettingsAuditor.evaluate(library, emptySet(), AppSettingsAuditor.DeviceSettingsSnapshot())
        assertTrue(none.items.isEmpty())
        assertFalse(none.repairNeeded)
    }

    // [PROGRAMMATIC] ASET-TEST-004 (negative)
    @Test
    fun testEvaluateAccessibilityRequiresGlobalSwitchAndComponent() {
        val library = listOf(entry(tqa, "TV Quick Actions", req(tqa, SettingType.ACCESSIBILITY_SERVICE, tqaA11y)))
        fun status(snapshot: AppSettingsAuditor.DeviceSettingsSnapshot) =
            AppSettingsAuditor.evaluate(library, setOf(tqa), snapshot).items.single().status

        // Master Bedroom GTV, 2026-09-11: global switch off, list empty.
        assertEquals(ItemStatus.MISSING, status(AppSettingsAuditor.DeviceSettingsSnapshot(accessibilityEnabled = false)))
        assertEquals(
            "listed but global switch off is still missing",
            ItemStatus.MISSING,
            status(AppSettingsAuditor.DeviceSettingsSnapshot(accessibilityEnabled = false, enabledAccessibilityServices = listOf(tqaA11y)))
        )
        assertEquals(
            "a different service of the same package does not count",
            ItemStatus.MISSING,
            status(AppSettingsAuditor.DeviceSettingsSnapshot(accessibilityEnabled = true, enabledAccessibilityServices = listOf("$tqa/.OtherService")))
        )
        // Great Room: enabled, among other services.
        assertEquals(
            ItemStatus.SATISFIED,
            status(
                AppSettingsAuditor.DeviceSettingsSnapshot(
                    accessibilityEnabled = true,
                    enabledAccessibilityServices = listOf("$projectivy/.services.ProjectivyAccessibilityService", tqaA11y, droidMeshA11y)
                )
            )
        )

        val projectivyLibrary = listOf(
            entry(projectivy, "Projectivy", req(projectivy, SettingType.ACCESSIBILITY_SERVICE, "$projectivy/com.spocky.projengmenu.services.ProjectivyAccessibilityService"))
        )
        assertEquals(
            "short form in settings equals full-form requirement",
            ItemStatus.SATISFIED,
            AppSettingsAuditor.evaluate(
                projectivyLibrary, setOf(projectivy),
                AppSettingsAuditor.DeviceSettingsSnapshot(
                    accessibilityEnabled = true,
                    enabledAccessibilityServices = listOf("$projectivy/.services.ProjectivyAccessibilityService")
                )
            ).items.single().status
        )
    }

    // [PROGRAMMATIC] ASET-TEST-005 (negative)
    @Test
    fun testEvaluateAppOpsUnverifiedUntilVerified() {
        val library = listOf(entry(kiosk, "Kiosk Satellite", req(kiosk, SettingType.APP_OP, "SYSTEM_ALERT_WINDOW")))

        val unverified = AppSettingsAuditor.evaluate(library, setOf(kiosk), AppSettingsAuditor.DeviceSettingsSnapshot())
        assertEquals(ItemStatus.UNVERIFIED, unverified.items.single().status)
        assertFalse("unverified alone never demands repair", unverified.repairNeeded)
        assertEquals(1, unverified.unverifiedCount)
        assertEquals(0, unverified.missingCount)

        assertEquals(
            "verifying a different op says nothing about this one",
            ItemStatus.UNVERIFIED,
            AppSettingsAuditor.evaluate(
                library, setOf(kiosk),
                AppSettingsAuditor.DeviceSettingsSnapshot(verifiedAppOps = mapOf(kiosk to mapOf("GET_USAGE_STATS" to true)))
            ).items.single().status
        )
        assertEquals(
            ItemStatus.SATISFIED,
            AppSettingsAuditor.evaluate(
                library, setOf(kiosk),
                AppSettingsAuditor.DeviceSettingsSnapshot(verifiedAppOps = mapOf(kiosk to mapOf("SYSTEM_ALERT_WINDOW" to true)))
            ).items.single().status
        )
        val missing = AppSettingsAuditor.evaluate(
            library, setOf(kiosk),
            AppSettingsAuditor.DeviceSettingsSnapshot(verifiedAppOps = mapOf(kiosk to mapOf("SYSTEM_ALERT_WINDOW" to false)))
        )
        assertEquals(ItemStatus.MISSING, missing.items.single().status)
        assertTrue(missing.repairNeeded)
    }

    // [PROGRAMMATIC] ASET-TEST-006 (negative)
    @Test
    fun testEvaluateEachInProcessTypeIndependently() {
        val home = "$projectivy/com.spocky.projengmenu.ui.home.MainActivity"
        val listener = "$projectivy/com.spocky.projengmenu.services.NotificationListener"
        val library = listOf(
            entry(
                projectivy, "Projectivy",
                req(projectivy, SettingType.DEFAULT_HOME, home),
                req(projectivy, SettingType.NOTIFICATION_LISTENER, listener),
                req(projectivy, SettingType.BATTERY_OPTIMIZATION),
                req(projectivy, SettingType.RUNTIME_PERMISSION, "android.permission.RECORD_AUDIO")
            )
        )
        val healthy = AppSettingsAuditor.DeviceSettingsSnapshot(
            enabledNotificationListeners = listOf("com.google.android.apps.tv.launcherx/com.google.x.Listener", listener),
            batteryExemptPackages = setOf(projectivy),
            defaultHomeComponent = "$projectivy/.ui.home.MainActivity",
            grantedPermissions = mapOf(projectivy to setOf("android.permission.RECORD_AUDIO"))
        )
        val allGood = AppSettingsAuditor.evaluate(library, setOf(projectivy), healthy)
        assertEquals(4, allGood.items.size)
        allGood.items.forEach { assertEquals(it.requirement.id, ItemStatus.SATISFIED, it.status) }
        assertFalse(allGood.repairNeeded)

        val broken = mapOf(
            SettingType.DEFAULT_HOME to healthy.copy(defaultHomeComponent = "com.google.android.apps.tv.launcherx/.home.HomeActivity"),
            SettingType.NOTIFICATION_LISTENER to healthy.copy(enabledNotificationListeners = listOf("com.google.android.apps.tv.launcherx/com.google.x.Listener")),
            SettingType.BATTERY_OPTIMIZATION to healthy.copy(batteryExemptPackages = setOf(tqa)),
            SettingType.RUNTIME_PERMISSION to healthy.copy(grantedPermissions = mapOf(tqa to setOf("android.permission.RECORD_AUDIO")))
        )
        for ((brokenType, snapshot) in broken) {
            val result = AppSettingsAuditor.evaluate(library, setOf(projectivy), snapshot)
            assertTrue(result.repairNeeded)
            assertEquals(1, result.missingCount)
            for (type in broken.keys) {
                val expected = if (type == brokenType) ItemStatus.MISSING else ItemStatus.SATISFIED
                assertEquals("broken=$brokenType checking=$type", expected, statusOf(result, type))
            }
        }
    }

    // [PROGRAMMATIC] ASET-TEST-007 (amended ASET-BEHAVE-014, gitea#100): an accessibility item's
    // command list now leads with the managed app's own ACCESS_RESTRICTED_SETTINGS clear, exactly
    // like ProvisioningAuditor.classify() already does for DroidMesh's own package (PROV-TEST-012).
    @Test
    fun testShellCommandsPerTypeAreExactAndAllowlisted() {
        val a11yReq = req(tqa, SettingType.ACCESSIBILITY_SERVICE, tqaA11y)
        val merged = AppSettingsAuditor.shellCommands(tqa, a11yReq, droidMeshA11y)
        assertEquals(
            listOf(
                "appops set $tqa ACCESS_RESTRICTED_SETTINGS allow",
                "settings put secure enabled_accessibility_services $droidMeshA11y:$tqaA11y",
                "settings put secure accessibility_enabled 1"
            ),
            merged
        )
        // Master Bedroom GTV: `settings get` prints the literal "null".
        assertEquals(
            "settings put secure enabled_accessibility_services $tqaA11y",
            AppSettingsAuditor.shellCommands(tqa, a11yReq, "null")[1]
        )
        val projectivyA11y = req(projectivy, SettingType.ACCESSIBILITY_SERVICE, "$projectivy/.services.ProjectivyAccessibilityService")
        assertEquals(
            "already present in short form: not duplicated, order preserved",
            "settings put secure enabled_accessibility_services $projectivy/.services.ProjectivyAccessibilityService:$droidMeshA11y",
            AppSettingsAuditor.shellCommands(projectivy, projectivyA11y, "$projectivy/.services.ProjectivyAccessibilityService:$droidMeshA11y")[1]
        )

        val listener = req(projectivy, SettingType.NOTIFICATION_LISTENER, "$projectivy/.services.NotificationListener")
        val battery = req(kiosk, SettingType.BATTERY_OPTIMIZATION)
        val op = req(kiosk, SettingType.APP_OP, "SYSTEM_ALERT_WINDOW")
        val home = req(projectivy, SettingType.DEFAULT_HOME, "$projectivy/.ui.home.MainActivity")
        val perm = req(projectivy, SettingType.RUNTIME_PERMISSION, "android.permission.RECORD_AUDIO")
        assertEquals(
            listOf("cmd notification allow_listener $projectivy/com.spocky.projengmenu.services.NotificationListener"),
            AppSettingsAuditor.shellCommands(projectivy, listener, null)
        )
        assertEquals(listOf("dumpsys deviceidle whitelist +$kiosk"), AppSettingsAuditor.shellCommands(kiosk, battery, null))
        assertEquals(listOf("appops set $kiosk SYSTEM_ALERT_WINDOW allow"), AppSettingsAuditor.shellCommands(kiosk, op, null))
        assertEquals(
            listOf("pm set-home-activity $projectivy/com.spocky.projengmenu.ui.home.MainActivity"),
            AppSettingsAuditor.shellCommands(projectivy, home, null)
        )
        assertEquals(listOf("pm grant $projectivy android.permission.RECORD_AUDIO"), AppSettingsAuditor.shellCommands(projectivy, perm, null))
        assertEquals("appops get $kiosk GET_USAGE_STATS", AppSettingsAuditor.appOpsReadCommand(kiosk, "GET_USAGE_STATS"))

        val every = merged +
            AppSettingsAuditor.shellCommands(projectivy, listener, null) +
            AppSettingsAuditor.shellCommands(kiosk, battery, null) +
            AppSettingsAuditor.shellCommands(kiosk, op, null) +
            AppSettingsAuditor.shellCommands(projectivy, home, null) +
            AppSettingsAuditor.shellCommands(projectivy, perm, null) +
            AppSettingsAuditor.appOpsReadCommand(kiosk, "GET_USAGE_STATS")
        every.forEach { assertTrue("must pass loopback allowlist: $it", AdbLoopbackInstaller.isAllowedShellCommand(it)) }

        assertEquals(
            "adb shell dumpsys deviceidle whitelist +$kiosk",
            AppSettingsAuditor.externalCommand(kiosk, battery, null)
        )
        assertEquals(
            "adb shell appops set $tqa ACCESS_RESTRICTED_SETTINGS allow && " +
                "adb shell settings put secure enabled_accessibility_services $droidMeshA11y:$tqaA11y && " +
                "adb shell settings put secure accessibility_enabled 1",
            AppSettingsAuditor.externalCommand(tqa, a11yReq, droidMeshA11y)
        )
    }

    // [PROGRAMMATIC] ASET-TEST-008 (negative) — output shapes captured live from Portal (SDK 29)
    // and Theater GTV (SDK 34) on 2026-09-11.
    @Test
    fun testParseAppOpsOutput() {
        assertTrue(AppSettingsAuditor.parseAppOpsOutput("GET_USAGE_STATS", "GET_USAGE_STATS: allow; time=+14s725ms ago\n"))
        assertTrue(AppSettingsAuditor.parseAppOpsOutput("SYSTEM_ALERT_WINDOW", "Uid mode: SYSTEM_ALERT_WINDOW: allow\n"))
        assertFalse(AppSettingsAuditor.parseAppOpsOutput("SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: default; rejectTime=+2d21h3m39s711ms ago"))
        assertFalse(AppSettingsAuditor.parseAppOpsOutput("GET_USAGE_STATS", "No operations.\nDefault mode: default"))
        assertFalse(
            "another op's allow line does not count",
            AppSettingsAuditor.parseAppOpsOutput("SYSTEM_ALERT_WINDOW", "GET_USAGE_STATS: allow; time=+1s ago")
        )
        assertFalse(AppSettingsAuditor.parseAppOpsOutput("WRITE_SETTINGS", "Error: No UID for $tqa in user 0"))
        assertFalse(AppSettingsAuditor.parseAppOpsOutput("WRITE_SETTINGS", "WRITE_SETTINGS: deny"))
    }

    // [PROGRAMMATIC] ASET-TEST-009
    @Test
    fun testCaptureRequirementsTakesOnlyTargetPackagesActiveSettings() {
        val snapshot = AppSettingsAuditor.DeviceSettingsSnapshot(
            accessibilityEnabled = true,
            enabledAccessibilityServices = listOf("$projectivy/.services.ProjectivyAccessibilityService", tqaA11y, droidMeshA11y),
            enabledNotificationListeners = listOf("com.google.android.apps.tv.launcherx/com.google.x.Listener"),
            batteryExemptPackages = setOf(tqa, "com.cfox.droidmesh"),
            defaultHomeComponent = "$projectivy/.ui.home.MainActivity",
            grantedPermissions = mapOf(
                tqa to setOf("android.permission.ACCESS_FINE_LOCATION"),
                projectivy to setOf("android.permission.RECORD_AUDIO")
            ),
            verifiedAppOps = mapOf(tqa to mapOf("SYSTEM_ALERT_WINDOW" to true, "WRITE_SETTINGS" to false))
        )
        assertEquals(
            setOf(
                req(tqa, SettingType.ACCESSIBILITY_SERVICE, tqaA11y),
                req(tqa, SettingType.BATTERY_OPTIMIZATION),
                req(tqa, SettingType.RUNTIME_PERMISSION, "android.permission.ACCESS_FINE_LOCATION"),
                req(tqa, SettingType.APP_OP, "SYSTEM_ALERT_WINDOW")
            ),
            AppSettingsAuditor.captureRequirements(tqa, snapshot).toSet()
        )
        assertEquals(
            setOf(
                req(projectivy, SettingType.ACCESSIBILITY_SERVICE, "$projectivy/.services.ProjectivyAccessibilityService"),
                req(projectivy, SettingType.DEFAULT_HOME, "$projectivy/.ui.home.MainActivity"),
                req(projectivy, SettingType.RUNTIME_PERMISSION, "android.permission.RECORD_AUDIO")
            ),
            AppSettingsAuditor.captureRequirements(projectivy, snapshot).toSet()
        )
        assertTrue(
            "global accessibility off: a listed service isn't actually active, so it isn't captured",
            AppSettingsAuditor.captureRequirements(tqa, snapshot.copy(accessibilityEnabled = false))
                .none { it.type == SettingType.ACCESSIBILITY_SERVICE }
        )
    }

    // [PROGRAMMATIC] ASET-TEST-013 (summary half; PeerNode half in PeerNodeAppSettingsTest)
    @Test
    fun testSummarizeCapsIssues() {
        val reqs = (1..10).map { req(tqa, SettingType.RUNTIME_PERMISSION, "android.permission.P$it") } +
            req(tqa, SettingType.APP_OP, "WRITE_SETTINGS")
        val library = listOf(entry(tqa, "TV Quick Actions", *reqs.toTypedArray()))
        val result = AppSettingsAuditor.evaluate(library, setOf(tqa), AppSettingsAuditor.DeviceSettingsSnapshot())
        val summary = AppSettingsAuditor.summarize(result)
        assertEquals(10, summary.missing)
        assertEquals(1, summary.unverified)
        assertEquals(8, summary.issues.size)
        assertTrue(summary.issues.all { it.startsWith("TV Quick Actions: ") })
        assertTrue("unverified items are not listed as issues", summary.issues.none { it.contains("WRITE_SETTINGS") })
    }

    // [PROGRAMMATIC] ASET-TEST-017 — repairPathFor() picks the mechanism before any I/O happens.
    // Accessibility items go straight to the direct write whenever WRITE_SECURE_SETTINGS is held,
    // regardless of the ADB auth gate's state, because that path never opens an ADB session at all.
    @Test
    fun testRepairPathForPrefersDirectWriteForAccessibilityRegardlessOfGate() {
        assertEquals(
            AppSettingsAuditor.RepairPath.DIRECT_WRITE,
            AppSettingsAuditor.repairPathFor(
                SettingType.ACCESSIBILITY_SERVICE,
                canWriteSecureSettingsDirectly = true,
                adbAuthGateTripped = false
            )
        )
        assertEquals(
            "direct write still wins even once the gate has tripped -- it doesn't touch ADB",
            AppSettingsAuditor.RepairPath.DIRECT_WRITE,
            AppSettingsAuditor.repairPathFor(
                SettingType.ACCESSIBILITY_SERVICE,
                canWriteSecureSettingsDirectly = true,
                adbAuthGateTripped = true
            )
        )
    }

    // [PROGRAMMATIC] ASET-TEST-017 (negative) — without the permission, accessibility falls back to
    // ADB like every other type, and is subject to the same gate.
    @Test
    fun testRepairPathForFallsBackToAdbWithoutPermissionAndRespectsGate() {
        assertEquals(
            AppSettingsAuditor.RepairPath.ADB,
            AppSettingsAuditor.repairPathFor(
                SettingType.ACCESSIBILITY_SERVICE,
                canWriteSecureSettingsDirectly = false,
                adbAuthGateTripped = false
            )
        )
        assertEquals(
            AppSettingsAuditor.RepairPath.SKIP_AUTH_PENDING,
            AppSettingsAuditor.repairPathFor(
                SettingType.ACCESSIBILITY_SERVICE,
                canWriteSecureSettingsDirectly = false,
                adbAuthGateTripped = true
            )
        )
        assertEquals(
            "a non-accessibility type has no direct-write path at all, so a tripped gate skips it too",
            AppSettingsAuditor.RepairPath.SKIP_AUTH_PENDING,
            AppSettingsAuditor.repairPathFor(
                SettingType.RUNTIME_PERMISSION,
                canWriteSecureSettingsDirectly = true,
                adbAuthGateTripped = true
            )
        )
        assertEquals(
            AppSettingsAuditor.RepairPath.ADB,
            AppSettingsAuditor.repairPathFor(
                SettingType.BATTERY_OPTIMIZATION,
                canWriteSecureSettingsDirectly = true,
                adbAuthGateTripped = false
            )
        )
    }

    // [PROGRAMMATIC] ASET-TEST-018 — the gate trips only for the one failure mode that means every
    // remaining ADB item in the batch is stuck behind the same unanswered prompt slot. Any other
    // failure (a bounced adbd, a malformed command) says nothing about the rest of the batch.
    @Test
    fun testAdbAuthGateTripsOnlyOnAuthorizationPendingFailure() {
        val gate = AppSettingsAuditor.AdbAuthGate()
        assertFalse(gate.shouldSkip())

        gate.record(Result.failure(IllegalStateException("ADB command failed: boom")))
        assertFalse("an unrelated failure must not trip the gate", gate.shouldSkip())

        gate.record(Result.success("ok"))
        assertFalse(gate.shouldSkip())

        gate.record(Result.failure(com.cfox.droidmesh.installer.AdbAuthorizationPendingException()))
        assertTrue("an auth-pending failure trips the gate for the rest of the batch", gate.shouldSkip())

        gate.record(Result.success("ok"))
        assertTrue("once tripped, the gate stays tripped for the rest of this repair() call", gate.shouldSkip())
    }

    // [PROGRAMMATIC] ASET-TEST-019 — a skipped item's failure text must be indistinguishable from
    // an actually-attempted item's, so the UI/repair-result consumer never has to special-case
    // "skipped" vs "attempted and failed" -- both say exactly what a person needs to do next.
    @Test
    fun testSkippedAuthPendingFailureMatchesAnActuallyAttemptedFailure() {
        val attempted = AppSettingsAuditor.describeRepairFailure(
            "runtime_permission:android.permission.BLUETOOTH_SCAN",
            com.cfox.droidmesh.installer.AdbAuthorizationPendingException()
        )
        val skipped = AppSettingsAuditor.skippedAuthPendingFailure(
            "runtime_permission:android.permission.BLUETOOTH_SCAN"
        )
        assertEquals(attempted.id, skipped.id)
        assertEquals(attempted.error, skipped.error)
    }

    // [PROGRAMMATIC] ASET-TEST-022 (ASET-BEHAVE-014, gitea#100): DEPRECATED ASET-TEST-020's claim
    // that a fully DIRECT_WRITE-covered repair never touches ADB -- it no longer holds. Every
    // accessibility item now clears ACCESS_RESTRICTED_SETTINGS for its own package over ADB before
    // the direct write (best-effort, same tolerance as ProvisioningAuditor's own package), so the
    // pre-flight probe (ASET-BEHAVE-013) must still run even when WRITE_SECURE_SETTINGS is held.
    @Test
    fun testNeedsAdbProbeIsTrueEvenWhenEveryRequirementIsDirectWriteCovered() {
        val entries = listOf(
            entry(tqa, "tvQuickActions", req(tqa, SettingType.ACCESSIBILITY_SERVICE, tqaA11y)),
            entry(kiosk, "Kiosk Satellite", req(kiosk, SettingType.ACCESSIBILITY_SERVICE, "$kiosk/x.KioskAccessibilityService"))
        )
        assertTrue(
            "an accessibility requirement always needs the probe now, DIRECT_WRITE included",
            AppSettingsAuditor.needsAdbProbe(entries, canWriteSecureSettingsDirectly = true)
        )
    }

    // [PROGRAMMATIC] ASET-TEST-021 (gitea#95, negative) — any requirement that repairPathFor would
    // ever route to ADB (a non-accessibility type, or accessibility without the permission) means
    // this repair can hit ADB, so the probe must run.
    @Test
    fun testNeedsAdbProbeIsTrueWhenAnyRequirementCanReachAdb() {
        val onlyAccessibilityButNoPermission = listOf(
            entry(tqa, "tvQuickActions", req(tqa, SettingType.ACCESSIBILITY_SERVICE, tqaA11y))
        )
        assertTrue(
            "without WRITE_SECURE_SETTINGS, accessibility itself falls back to ADB",
            AppSettingsAuditor.needsAdbProbe(onlyAccessibilityButNoPermission, canWriteSecureSettingsDirectly = false)
        )

        val mixedWithBatteryOptimization = listOf(
            entry(kiosk, "Kiosk Satellite",
                req(kiosk, SettingType.ACCESSIBILITY_SERVICE, "$kiosk/x.KioskAccessibilityService"),
                req(kiosk, SettingType.BATTERY_OPTIMIZATION)
            )
        )
        assertTrue(
            "battery optimization has no direct-write path regardless of the accessibility item",
            AppSettingsAuditor.needsAdbProbe(mixedWithBatteryOptimization, canWriteSecureSettingsDirectly = true)
        )

        val runtimePermission = listOf(
            entry(kiosk, "Kiosk Satellite", req(kiosk, SettingType.RUNTIME_PERMISSION, "android.permission.BLUETOOTH_SCAN"))
        )
        assertTrue(
            AppSettingsAuditor.needsAdbProbe(runtimePermission, canWriteSecureSettingsDirectly = true)
        )
    }
}
