package com.cfox.droidmesh.downloader

import android.content.Context
import android.os.Environment
import com.cfox.droidmesh.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class ApkDownloader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    companion object {
        private val SAFE_FILENAME_REGEX = Regex("^[A-Za-z0-9._-]{1,255}$")

        /**
         * [UPD-BEHAVE-015] Whitelists APK file names to a safe charset before they ever reach a
         * File() construction or a shell command line. gitea#53: an unsanitized filename/tag param
         * on /update flowed verbatim into ApkDownloader's File(downloadDir, targetFileName) and
         * then into AdbLoopbackInstaller's unescaped `cat "<path>" | pm install ...` shell string --
         * shell metacharacters (quotes, `;`, `|`, backticks) in the filename broke out of the
         * quoted path and ran arbitrary shell commands with the app's privileges. A bare ".." also
         * resolves to the parent directory via File(dir, "..") even with no "/" in the name, so it
         * is rejected explicitly alongside the character whitelist (closes the related path
         * traversal gap, gitea#58, with the same check).
         */
        fun isSafeApkFileName(name: String): Boolean {
            if (name == "." || name == "..") return false
            return SAFE_FILENAME_REGEX.matches(name)
        }
    }

    /**
     * Downloads an APK from the provided url into Scoped Storage safe directory
     * (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) with byte-stream progress tracking.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        targetFileName: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!com.cfox.droidmesh.security.TrustedReleaseHosts.isTrustedReleaseUrl(downloadUrl)) {
                val err = "Insecure or untrusted APK download URL: $downloadUrl (HTTPS and trusted release host required)"
                Logger.e(err)
                return@withContext Result.failure(SecurityException(err))
            }

            if (!isSafeApkFileName(targetFileName)) {
                val err = "Invalid or unsafe APK file name: $targetFileName"
                Logger.e(err)
                return@withContext Result.failure(SecurityException(err))
            }

            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")

            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }

            val finalApkFile = File(downloadDir, targetFileName)
            val partFile = File(downloadDir, "$targetFileName.part")

            var existingLength = 0L
            if (partFile.exists()) {
                existingLength = partFile.length()
            }

            Logger.i("Starting download for $targetFileName from $downloadUrl (resume offset: $existingLength bytes)")

            val requestBuilder = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "DroidMesh-Android")

            if (existingLength > 0L) {
                requestBuilder.header("Range", "bytes=$existingLength-")
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful && response.code != 206) {
                // If Range not satisfiable (e.g. HTTP 416), delete part file and retry from 0
                if (response.code == 416) {
                    Logger.w("HTTP 416 Range Not Satisfiable, restarting full download")
                    partFile.delete()
                    return@withContext downloadApk(downloadUrl, targetFileName, onProgress)
                }
                val code = response.code
                response.close()
                Logger.e("Download request failed with HTTP code $code")
                return@withContext Result.failure(IOException("Server returned HTTP $code"))
            }

            val responseBody = response.body ?: throw IOException("Empty response body for APK download")
            val isPartialContent = (response.code == 206)

            val contentLength = responseBody.contentLength()
            val totalBytes = if (isPartialContent) {
                existingLength + contentLength
            } else {
                // Server ignored Range or full download started
                if (!isPartialContent && existingLength > 0) {
                    partFile.delete()
                    existingLength = 0L
                }
                contentLength
            }

            var bytesDownloaded = existingLength
            var lastReportedPercent = -1

            val inputStream: InputStream = responseBody.byteStream()
            val outputStream = if (isPartialContent && existingLength > 0L) {
                RandomAccessFile(partFile, "rw").apply {
                    seek(existingLength)
                }
            } else {
                FileOutputStream(partFile, false)
            }

            val buffer = ByteArray(32 * 1024) // 32KB buffer
            var bytesRead: Int

            try {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (outputStream is RandomAccessFile) {
                        outputStream.write(buffer, 0, bytesRead)
                    } else if (outputStream is FileOutputStream) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    bytesDownloaded += bytesRead

                    val percent = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        onProgress(
                            DownloadProgress(
                                bytesRead = bytesDownloaded,
                                totalBytes = totalBytes,
                                progressPercent = percent,
                                isDone = false
                            )
                        )
                    }
                }
            } finally {
                try {
                    if (outputStream is RandomAccessFile) outputStream.close()
                    else if (outputStream is FileOutputStream) outputStream.close()
                } catch (e: Exception) {
                    Logger.w("Error closing output stream: ${e.message}")
                }
                try {
                    inputStream.close()
                } catch (e: Exception) {
                    Logger.w("Error closing input stream: ${e.message}")
                }
                response.close()
            }

            // Rename part file to final APK file
            if (finalApkFile.exists()) {
                finalApkFile.delete()
            }

            if (!partFile.renameTo(finalApkFile)) {
                // Fallback copy if rename fails
                partFile.copyTo(finalApkFile, overwrite = true)
                partFile.delete()
            }

            Logger.i("APK downloaded successfully: ${finalApkFile.absolutePath} (${finalApkFile.length()} bytes)")
            onProgress(
                DownloadProgress(
                    bytesRead = finalApkFile.length(),
                    totalBytes = finalApkFile.length(),
                    progressPercent = 100,
                    isDone = true
                )
            )

            Result.success(finalApkFile)
        } catch (e: Exception) {
            Logger.e("Download failed", e)
            onProgress(
                DownloadProgress(
                    bytesRead = 0,
                    totalBytes = 0,
                    progressPercent = 0,
                    isDone = false,
                    error = e.message
                )
            )
            Result.failure(e)
        }
    }
}
