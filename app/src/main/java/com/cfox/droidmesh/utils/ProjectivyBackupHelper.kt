package com.cfox.droidmesh.utils

import android.content.Context
import java.io.File

object ProjectivyBackupHelper {
    const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"
    const val PRIMARY_BACKUPS_DIR = "/storage/emulated/0/Android/data/com.spocky.projengmenu/files/backups"
    const val DOWNLOADS_DIR = "/storage/emulated/0/Download"

    /**
     * [APP-BEHAVE-008]: Scans candidate directories for .plbackup files and returns the newest file
     * based on lastModified timestamp, or null if none are found.
     */
    fun findLatestBackup(context: Context? = null, searchDirs: List<File>? = null): File? {
        val dirsToScan = searchDirs ?: buildList {
            add(File(PRIMARY_BACKUPS_DIR))
            add(File(DOWNLOADS_DIR))
            context?.getExternalFilesDir(null)?.let { add(it) }
            context?.filesDir?.let { add(it) }
        }

        var newestFile: File? = null
        var newestTimestamp = -1L

        for (dir in dirsToScan) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { f ->
                f.isFile && f.name.endsWith(".plbackup", ignoreCase = true) && f.length() > 0
            } ?: continue

            for (file in files) {
                val modified = file.lastModified()
                if (modified > newestTimestamp) {
                    newestTimestamp = modified
                    newestFile = file
                }
            }
        }
        return newestFile
    }

    fun getDestinationBackupFile(context: Context? = null, targetDir: File? = null): File {
        if (targetDir != null) {
            targetDir.mkdirs()
            return File(targetDir, "mesh-synced-projectivy.plbackup")
        }
        val primaryDir = File(PRIMARY_BACKUPS_DIR)
        if (primaryDir.exists() && primaryDir.canWrite()) {
            return File(primaryDir, "mesh-synced-projectivy.plbackup")
        }
        // Fall back to Download directory or context external files dir
        val downloadDir = File(DOWNLOADS_DIR)
        if (downloadDir.exists() && downloadDir.canWrite()) {
            return File(downloadDir, "mesh-synced-projectivy.plbackup")
        }
        val contextDir = context?.getExternalFilesDir(null) ?: File("/sdcard/Download")
        contextDir.mkdirs()
        return File(contextDir, "mesh-synced-projectivy.plbackup")
    }
}
