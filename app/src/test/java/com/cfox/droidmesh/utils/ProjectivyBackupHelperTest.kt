package com.cfox.droidmesh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectivyBackupHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // [PROGRAMMATIC] APP-TEST-011: ProjectivyBackupHelper.findLatestBackup returns the newest .plbackup file
    // across scanned locations when multiple exist.
    @Test
    fun testFindLatestBackupSelectsNewest() {
        val dir1 = tempFolder.newFolder("primary_backups")
        val dir2 = tempFolder.newFolder("downloads")

        val olderFile = File(dir1, "projectivy-settings-20260704_013626.plbackup").apply {
            writeText("old backup")
            setLastModified(1_000_000L)
        }

        val middleFile = File(dir2, "__auto_projectivy-settings-20260714_110359.plbackup").apply {
            writeText("middle backup")
            setLastModified(2_000_000L)
        }

        val newestFile = File(dir1, "projectivy-settings-20260823_155748.plbackup").apply {
            writeText("newest backup")
            setLastModified(3_000_000L)
        }

        // Add a non-matching file with even newer timestamp to ensure extension filter works
        File(dir1, "other_file-20260901.txt").apply {
            writeText("other file")
            setLastModified(4_000_000L)
        }


        val found = ProjectivyBackupHelper.findLatestBackup(searchDirs = listOf(dir1, dir2))
        assertNotNull(found)
        assertEquals(newestFile.absolutePath, found?.absolutePath)
    }

    // [PROGRAMMATIC] APP-TEST-012: ProjectivyBackupHelper.findLatestBackup returns null when directories are
    // missing, empty, or contain only non-.plbackup files.
    @Test
    fun testFindLatestBackupReturnsNullWhenNone() {
        val emptyDir = tempFolder.newFolder("empty_dir")
        val missingDir = File(tempFolder.root, "does_not_exist")
        val otherFilesDir = tempFolder.newFolder("other_files").apply {
            File(this, "backup.zip").createNewFile()
            File(this, "projectivy.apk").createNewFile()
            File(this, "settings.json").createNewFile()
        }

        val resultEmpty = ProjectivyBackupHelper.findLatestBackup(searchDirs = listOf(emptyDir, missingDir, otherFilesDir))
        assertNull(resultEmpty)

        val resultMissing = ProjectivyBackupHelper.findLatestBackup(searchDirs = listOf(missingDir))
        assertNull(resultMissing)
    }
}
