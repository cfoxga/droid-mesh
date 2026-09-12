package com.cfox.droidmesh.downloader

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkRetentionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // [PROGRAMMATIC] UPD-TEST-019: Pruning retains only 2 newest versions and deletes older APKs
    @Test
    fun testPruneDeletesOlderVersionsExceedingRetentionLimit() {
        val downloadDir = tempFolder.newFolder("downloads")
        val apk1 = File(downloadDir, "kiosk-satellite-2026.9.37.apk").apply {
            writeText("dummy-apk-1")
            setLastModified(1000L)
        }
        val apk2 = File(downloadDir, "kiosk-satellite-2026.9.38.apk").apply {
            writeText("dummy-apk-2")
            setLastModified(2000L)
        }
        val apk3 = File(downloadDir, "kiosk-satellite-2026.9.39.apk").apply {
            writeText("dummy-apk-3")
            setLastModified(3000L)
        }
        val apk4 = File(downloadDir, "kiosk-satellite-2026.9.40.apk").apply {
            writeText("dummy-apk-4")
            setLastModified(4000L)
        }

        // Multiple apps in same directory
        val other1 = File(downloadDir, "other-app-1.0.0.apk").apply {
            writeText("other-1")
            setLastModified(1000L)
        }
        val other2 = File(downloadDir, "other-app-1.1.0.apk").apply {
            writeText("other-2")
            setLastModified(2000L)
        }
        val other3 = File(downloadDir, "other-app-1.2.0.apk").apply {
            writeText("other-3")
            setLastModified(3000L)
        }

        val deleted = ApkRetentionManager.prune(
            downloadDir = downloadDir,
            maxVersionsPerApp = 2,
            resolver = null
        )

        // For kiosk-satellite: 37 and 38 should be deleted; 39 and 40 retained
        assertFalse("Oldest apk1 should be deleted", apk1.exists())
        assertFalse("Older apk2 should be deleted", apk2.exists())
        assertTrue("Recent apk3 should be retained", apk3.exists())
        assertTrue("Latest apk4 should be retained", apk4.exists())

        // For other-app: other1 deleted; other2 and other3 retained
        assertFalse("Old other1 should be deleted", other1.exists())
        assertTrue("Recent other2 should be retained", other2.exists())
        assertTrue("Latest other3 should be retained", other3.exists())

        assertEquals(3, deleted.size)
        assertTrue(deleted.contains(apk1))
        assertTrue(deleted.contains(apk2))
        assertTrue(deleted.contains(other1))
    }

    // [PROGRAMMATIC] UPD-TEST-020: Pruning retains 2 or fewer versions and cleans up orphaned .part files
    @Test
    fun testPruneRetainsTwoOrFewerVersionsAndCleansOrphanedParts() {
        val downloadDir = tempFolder.newFolder("downloads")
        val apk1 = File(downloadDir, "kiosk-satellite-2026.9.39.apk").apply {
            writeText("dummy-apk-1")
            setLastModified(1000L)
        }
        val apk2 = File(downloadDir, "kiosk-satellite-2026.9.40.apk").apply {
            writeText("dummy-apk-2")
            setLastModified(2000L)
        }

        // Active .part file for one of the retained APKs
        val activePart = File(downloadDir, "kiosk-satellite-2026.9.40.apk.part").apply {
            writeText("partial-download")
            setLastModified(System.currentTimeMillis())
        }

        // Orphaned .part file for an old/non-existent APK
        val orphanedPart = File(downloadDir, "kiosk-satellite-2026.9.20.apk.part").apply {
            writeText("abandoned-download")
            setLastModified(System.currentTimeMillis() - 2 * 86400 * 1000L) // 2 days old
        }

        val deleted = ApkRetentionManager.prune(
            downloadDir = downloadDir,
            maxVersionsPerApp = 2,
            resolver = null
        )

        assertTrue("apk1 should still exist", apk1.exists())
        assertTrue("apk2 should still exist", apk2.exists())
        assertTrue("Active part should still exist", activePart.exists())
        assertFalse("Orphaned part should be deleted", orphanedPart.exists())

        assertEquals(1, deleted.size)
        assertTrue(deleted.contains(orphanedPart))
    }
}
