package com.cfox.droidmesh.service

import com.cfox.droidmesh.settings.SettingsStore.MeshAppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PROGRAMMATIC] FLT-TEST-003 / FLT-TEST-004: pure decision-logic coverage for
 * MeshAutoActionPlanner.plan() — no Android dependencies, so this runs as a plain JVM test.
 */
class MeshAutoActionPlannerTest {

    private fun cfg(
        pkg: String,
        managed: Boolean = true,
        autoInstall: Boolean = false,
        autoUpdate: Boolean = false,
        isSideloaded: Boolean = true,
        downloadUrl: String = "https://example.com/$pkg.apk"
    ) = MeshAppConfig(
        packageName = pkg,
        appName = pkg,
        managed = managed,
        autoInstall = autoInstall,
        autoUpdate = autoUpdate,
        isSideloaded = isSideloaded,
        downloadUrl = downloadUrl
    )

    private val notExcluded: (String) -> Boolean = { false }

    // FLT-TEST-003: full-eligibility install candidate
    @Test
    fun testInstallCandidateWhenAllConditionsMet() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoInstall = true))
        val plan = MeshAutoActionPlanner.plan(library, installedPackages = emptySet(), isExcluded = notExcluded)
        assertEquals(listOf("com.example.app"), plan.installs.map { it.packageName })
    }

    // FLT-TEST-003 (negative): managed=false excludes from installs
    @Test
    fun testInstallExcludedWhenNotManaged() {
        val library = mapOf("com.example.app" to cfg("com.example.app", managed = false, autoInstall = true))
        val plan = MeshAutoActionPlanner.plan(library, installedPackages = emptySet(), isExcluded = notExcluded)
        assertTrue(plan.installs.isEmpty())
    }

    // FLT-TEST-003 (negative): autoInstall=false excludes from installs
    @Test
    fun testInstallExcludedWhenAutoInstallFalse() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoInstall = false))
        val plan = MeshAutoActionPlanner.plan(library, installedPackages = emptySet(), isExcluded = notExcluded)
        assertTrue(plan.installs.isEmpty())
    }

    // FLT-TEST-005: isSideloaded is descriptive origin metadata, NOT a manageability gate
    // (APP-BEHAVE-006). A store-origin entry that an admin has given a downloadUrl and marked
    // managed + autoInstall IS an install candidate. Before this, the planner silently dropped
    // it while the UI still rendered an enabled Auto Install checkbox for it.
    @Test
    fun testInstallIncludedWhenNotSideloadedButHasDownloadUrl() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoInstall = true, isSideloaded = false))
        val plan = MeshAutoActionPlanner.plan(library, installedPackages = emptySet(), isExcluded = notExcluded)
        assertEquals(listOf("com.example.app"), plan.installs.map { it.packageName })
    }

    // FLT-TEST-005 (negative): origin alone never promotes an entry — a sideloaded-origin entry
    // with a blank downloadUrl is still excluded, proving downloadUrl is the real gate.
    @Test
    fun testInstallExcludedWhenSideloadedButDownloadUrlBlank() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoInstall = true, isSideloaded = true, downloadUrl = ""))
        val plan = MeshAutoActionPlanner.plan(library, installedPackages = emptySet(), isExcluded = notExcluded)
        assertTrue(plan.installs.isEmpty())
    }

    // FLT-TEST-003 (negative): blank downloadUrl excludes from installs
    @Test
    fun testInstallExcludedWhenDownloadUrlBlank() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoInstall = true, downloadUrl = ""))
        val plan = MeshAutoActionPlanner.plan(library, installedPackages = emptySet(), isExcluded = notExcluded)
        assertTrue(plan.installs.isEmpty())
    }

    // FLT-TEST-003 (negative): already-installed excludes from installs
    @Test
    fun testInstallExcludedWhenAlreadyInstalled() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoInstall = true))
        val plan = MeshAutoActionPlanner.plan(
            library,
            installedPackages = setOf("com.example.app"),
            isExcluded = notExcluded
        )
        assertTrue(plan.installs.isEmpty())
    }

    // FLT-TEST-003 (negative): OEM/system-excluded package excludes from installs
    @Test
    fun testInstallExcludedWhenPackageIsExcluded() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoInstall = true))
        val plan = MeshAutoActionPlanner.plan(library, installedPackages = emptySet(), isExcluded = { true })
        assertTrue(plan.installs.isEmpty())
    }

    // FLT-TEST-004: full-eligibility update candidate — installed, managed, autoUpdate
    @Test
    fun testUpdateCheckCandidateWhenInstalledAndManagedAndAutoUpdate() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoUpdate = true))
        val plan = MeshAutoActionPlanner.plan(
            library,
            installedPackages = setOf("com.example.app"),
            isExcluded = notExcluded
        )
        assertEquals(listOf("com.example.app"), plan.updateChecks.map { it.packageName })
    }

    // FLT-TEST-004 (negative): not installed excludes from updateChecks (nothing to update)
    @Test
    fun testUpdateCheckExcludedWhenNotInstalled() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoUpdate = true))
        val plan = MeshAutoActionPlanner.plan(library, installedPackages = emptySet(), isExcluded = notExcluded)
        assertTrue(plan.updateChecks.isEmpty())
    }

    // FLT-TEST-004 (negative): autoUpdate=false excludes from updateChecks
    @Test
    fun testUpdateCheckExcludedWhenAutoUpdateFalse() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoUpdate = false))
        val plan = MeshAutoActionPlanner.plan(
            library,
            installedPackages = setOf("com.example.app"),
            isExcluded = notExcluded
        )
        assertTrue(plan.updateChecks.isEmpty())
    }

    // FLT-TEST-004 (negative): managed=false excludes from updateChecks
    @Test
    fun testUpdateCheckExcludedWhenNotManaged() {
        val library = mapOf("com.example.app" to cfg("com.example.app", managed = false, autoUpdate = true))
        val plan = MeshAutoActionPlanner.plan(
            library,
            installedPackages = setOf("com.example.app"),
            isExcluded = notExcluded
        )
        assertTrue(plan.updateChecks.isEmpty())
    }

    // FLT-TEST-004 (negative): blank downloadUrl excludes from updateChecks
    @Test
    fun testUpdateCheckExcludedWhenDownloadUrlBlank() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoUpdate = true, downloadUrl = ""))
        val plan = MeshAutoActionPlanner.plan(
            library,
            installedPackages = setOf("com.example.app"),
            isExcluded = notExcluded
        )
        assertTrue(plan.updateChecks.isEmpty())
    }

    // FLT-TEST-004: no singleton constraint — two entries simultaneously managed+autoUpdate
    // both appear in the same plan, proving the deleted single-managed-app resolver has no
    // successor constraint here.
    @Test
    fun testMultipleSimultaneousManagedAutoUpdateEntriesBothPlanned() {
        val library = mapOf(
            "com.example.appone" to cfg("com.example.appone", autoUpdate = true),
            "com.example.apptwo" to cfg("com.example.apptwo", autoUpdate = true)
        )
        val plan = MeshAutoActionPlanner.plan(
            library,
            installedPackages = setOf("com.example.appone", "com.example.apptwo"),
            isExcluded = notExcluded
        )
        assertEquals(
            setOf("com.example.appone", "com.example.apptwo"),
            plan.updateChecks.map { it.packageName }.toSet()
        )
    }

    // ---- FLT-BEHAVE-007: auto-update must honor the pinned targetVersion ----

    private fun rel(tag: String) = com.cfox.droidmesh.api.ReleaseInfo(
        tagName = tag, name = tag, publishedAt = "",
        apkAssetUrl = "https://example.com/$tag.apk",
        apkFileName = "$tag.apk", apkSize = 1L
    )

    private val kioskReleases = listOf(rel("2026.9.3"), rel("2026.9.2"), rel("2026.8.108"), rel("2026.8.107"))

    private fun pinned(target: String) = MeshAppConfig(
        packageName = "me.jxl.kiosk_satellite",
        appName = "Kiosk Satellite",
        managed = true,
        autoUpdate = true,
        targetVersion = target,
        downloadUrl = "https://github.com/jxlarrea/kiosk-satellite/releases"
    )

    // [PROGRAMMATIC] FLT-TEST-006: a pinned targetVersion installs THAT release, not the newest.
    @Test
    fun testAutoUpdateInstallsThePinnedReleaseNotTheNewest() {
        val action = MeshAutoActionPlanner.decideUpdate(pinned("2026.9.2"), "2026.8.107", kioskReleases)
        assertTrue("expected an Install, got $action", action is MeshAutoActionPlanner.UpdateAction.Install)
        assertEquals(
            "auto-update must install the pinned tag, not the newest release",
            "2026.9.2",
            (action as MeshAutoActionPlanner.UpdateAction.Install).release.tagName
        )
    }

    // [PROGRAMMATIC] FLT-TEST-006 (negative): already on the pinned version -> no action, so the
    // hourly loop does not reinstall the same build forever.
    @Test
    fun testAutoUpdateSkipsWhenAlreadyOnThePinnedRelease() {
        val action = MeshAutoActionPlanner.decideUpdate(pinned("2026.9.2"), "2026.9.2", kioskReleases)
        assertTrue("expected a Skip, got $action", action is MeshAutoActionPlanner.UpdateAction.Skip)
    }

    // [PROGRAMMATIC] FLT-TEST-006 (negative): a pin OLDER than what is installed is never an
    // install attempt — Android rejects downgrades, so retrying hourly would just fail forever.
    @Test
    fun testAutoUpdateRefusesToDowngradeToAnOlderPin() {
        val action = MeshAutoActionPlanner.decideUpdate(pinned("2026.8.108"), "2026.9.3", kioskReleases)
        assertTrue("expected a Skip, got $action", action is MeshAutoActionPlanner.UpdateAction.Skip)
        assertTrue(
            "the skip reason must name the downgrade, got: $action",
            (action as MeshAutoActionPlanner.UpdateAction.Skip).reason.contains("downgrade", ignoreCase = true)
        )
    }

    // [PROGRAMMATIC] FLT-TEST-006 (negative): a pin matching no published release is a skip whose
    // reason names the tag — never a silent fall-back to installing the newest build.
    @Test
    fun testAutoUpdateSkipsWhenPinMatchesNoRelease() {
        val action = MeshAutoActionPlanner.decideUpdate(pinned("2026.9.99"), "2026.8.107", kioskReleases)
        assertTrue("expected a Skip, got $action", action is MeshAutoActionPlanner.UpdateAction.Skip)
        assertTrue(
            "the skip reason must name the unmatched tag, got: $action",
            (action as MeshAutoActionPlanner.UpdateAction.Skip).reason.contains("2026.9.99")
        )
    }

    // [PROGRAMMATIC] FLT-TEST-006: "latest" (and blank) still mean newest published release.
    @Test
    fun testAutoUpdateStillTracksNewestWhenTargetIsLatest() {
        listOf("latest", "").forEach { target ->
            val action = MeshAutoActionPlanner.decideUpdate(pinned(target), "2026.8.107", kioskReleases)
            assertTrue("expected an Install for target='$target', got $action",
                action is MeshAutoActionPlanner.UpdateAction.Install)
            assertEquals(
                "2026.9.3",
                (action as MeshAutoActionPlanner.UpdateAction.Install).release.tagName
            )
        }
    }
}
