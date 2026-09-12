package com.cfox.droidmesh.downloader

import android.content.Context
import android.os.Build
import android.os.Environment
import com.cfox.droidmesh.utils.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [UPD-BEHAVE-017] Enforces an APK retention policy (maximum 2 versions per app)
 * in Scoped Storage download directories to prevent internal storage exhaustion.
 */
object ApkRetentionManager {

    const val DEFAULT_MAX_VERSIONS_PER_APP = 2
    private val STALE_PART_THRESHOLD_MS = TimeUnit.HOURS.toMillis(24)
    private val VERSION_SPLIT_REGEX = Regex("^(.*?)[-_][vV]?(\\d+(?:\\.\\d+)*.*?)$")

    class CandidateApk(
        val file: File,
        val appIdentifier: String,
        val versionCode: Long,
        val lastModified: Long
    )

    fun prune(
        downloadDir: File,
        maxVersionsPerApp: Int = DEFAULT_MAX_VERSIONS_PER_APP,
        resolver: ((File) -> Pair<String, Long>?)? = null
    ): List<File> {
        if (!downloadDir.exists() || !downloadDir.isDirectory) {
            return emptyList()
        }

        val allFiles = downloadDir.listFiles() ?: return emptyList()
        val deletedFiles = mutableListOf<File>()

        val apkFiles = allFiles.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
        val partFiles = allFiles.filter { it.isFile && it.name.endsWith(".apk.part", ignoreCase = true) }

        val candidates = apkFiles.map { file ->
            val resolved = resolver?.invoke(file)
            if (resolved != null) {
                CandidateApk(
                    file = file,
                    appIdentifier = resolved.first,
                    versionCode = resolved.second,
                    lastModified = file.lastModified()
                )
            } else {
                val (appId, vCode) = parseAppAndVersionFromFileName(file.name)
                CandidateApk(
                    file = file,
                    appIdentifier = appId,
                    versionCode = vCode,
                    lastModified = file.lastModified()
                )
            }
        }

        val grouped = candidates.groupBy { it.appIdentifier }
        val retainedFiles = mutableSetOf<File>()

        for ((appId, group) in grouped) {
            val sorted = group.sortedWith(
                compareByDescending<CandidateApk> { it.versionCode }
                    .thenByDescending { it.lastModified }
                    .thenByDescending { it.file.name }
            )

            val toKeep = sorted.take(maxVersionsPerApp)
            val toDelete = sorted.drop(maxVersionsPerApp)

            toKeep.forEach { retainedFiles.add(it.file) }

            for (candidate in toDelete) {
                Logger.i("ApkRetentionManager: Pruning obsolete APK ${candidate.file.name} for $appId")
                if (candidate.file.delete()) {
                    deletedFiles.add(candidate.file)
                }
            }
        }

        val now = System.currentTimeMillis()
        for (part in partFiles) {
            val baseApkName = part.name.removeSuffix(".part")
            val baseApkFile = File(downloadDir, baseApkName)
            val isRetained = retainedFiles.contains(baseApkFile)
            val isStale = (now - part.lastModified()) > STALE_PART_THRESHOLD_MS

            if (!isRetained || isStale) {
                Logger.i("ApkRetentionManager: Pruning stale/orphaned partial download ${part.name}")
                if (part.delete()) {
                    deletedFiles.add(part)
                }
            }
        }

        return deletedFiles
    }

    fun prune(
        context: Context,
        maxVersionsPerApp: Int = DEFAULT_MAX_VERSIONS_PER_APP
    ): List<File> {
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")

        val resolver: (File) -> Pair<String, Long>? = { file ->
            try {
                val pkgInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                if (pkgInfo != null && !pkgInfo.packageName.isNullOrBlank()) {
                    val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pkgInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pkgInfo.versionCode.toLong()
                    }
                    Pair(pkgInfo.packageName, vCode)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        return prune(downloadDir, maxVersionsPerApp, resolver)
    }

    private fun parseAppAndVersionFromFileName(fileName: String): Pair<String, Long> {
        val baseName = fileName.removeSuffix(".apk")
        val match = VERSION_SPLIT_REGEX.find(baseName)
        if (match != null) {
            val appPrefix = match.groupValues[1]
            val versionStr = match.groupValues[2]
            val numericCode = parseVersionCodeFromString(versionStr)
            return Pair(appPrefix, numericCode)
        }

        val lastSep = baseName.lastIndexOfAny(charArrayOf('-', '_'))
        if (lastSep > 0) {
            val appPrefix = baseName.substring(0, lastSep)
            val versionStr = baseName.substring(lastSep + 1)
            val numericCode = parseVersionCodeFromString(versionStr)
            return Pair(appPrefix, numericCode)
        }

        return Pair(baseName, 0L)
    }

    private fun parseVersionCodeFromString(versionStr: String): Long {
        val cleaned = versionStr.removePrefix("v").removePrefix("V")
        val parts = cleaned.split(".").mapNotNull { part ->
            part.filter { it.isDigit() }.toLongOrNull()
        }
        if (parts.isEmpty()) return 0L

        var multiplier = 1_000_000L
        var code = 0L
        for (p in parts.take(3)) {
            code += p * multiplier
            multiplier /= 1_000L
        }
        return code
    }
}
