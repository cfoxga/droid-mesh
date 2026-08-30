package com.cfox.kiosksatelliteupdater.downloader

data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val progressPercent: Int,
    val isDone: Boolean = false,
    val error: String? = null
)
