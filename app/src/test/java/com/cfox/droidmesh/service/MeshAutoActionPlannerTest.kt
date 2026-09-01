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

    // FLT-TEST-003 (negative): isSideloaded=false excludes from installs
    @Test
    fun testInstallExcludedWhenNotSideloaded() {
        val library = mapOf("com.example.app" to cfg("com.example.app", autoInstall = true, isSideloaded = false))
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
}
