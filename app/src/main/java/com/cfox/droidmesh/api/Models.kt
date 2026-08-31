package com.cfox.droidmesh.api

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val publishedAt: String,
    val apkAssetUrl: String,
    val apkFileName: String,
    val apkSize: Long
)

data class VersionComparison(
    val installedVersionName: String?,
    val installedVersionCode: Long?,
    val latestVersionTag: String,
    val isUpdateAvailable: Boolean,
    val releaseInfo: ReleaseInfo
)

data class UpdateStatus(
    val state: String, // IDLE, CHECKING, DOWNLOADING, INSTALLING, COMPLETED, ERROR
    val message: String,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null
)
