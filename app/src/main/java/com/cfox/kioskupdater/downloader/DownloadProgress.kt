package com.cfox.kioskupdater.downloader

data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val progressPercent: Int,
    val isDone: Boolean = false,
    val error: String? = null
)
