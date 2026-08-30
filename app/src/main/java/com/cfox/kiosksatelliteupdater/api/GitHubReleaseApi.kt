package com.cfox.kiosksatelliteupdater.api

import com.cfox.kiosksatelliteupdater.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubReleaseApi(
    private val repoOwner: String = "jxlarrea",
    private val repoName: String = "kiosk-satellite",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    suspend fun fetchLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
        Logger.i("Fetching latest release from $url")

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "KioskSatelliteUpdater-Android")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val errBody = response.body?.string() ?: "Empty body"
                    Logger.e("GitHub API error HTTP $code: $errBody")
                    return@withContext Result.failure(IOException("GitHub API responded with code $code: $errBody"))
                }

                val bodyString = response.body?.string() ?: throw IOException("Empty response body from GitHub")
                val json = JSONObject(bodyString)

                val tagName = json.optString("tag_name", "")
                val name = json.optString("name", tagName)
                val publishedAt = json.optString("published_at", "")
                val assets = json.optJSONArray("assets")

                if (assets == null || assets.length() == 0) {
                    Logger.w("Release $tagName has no assets attached")
                    return@withContext Result.failure(IllegalStateException("No assets found for release $tagName"))
                }

                var apkDownloadUrl: String? = null
                var apkFileName: String? = null
                var apkSize: Long = 0L

                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    val downloadUrl = asset.optString("browser_download_url", "")
                    val size = asset.optLong("size", 0L)

                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = downloadUrl
                        apkFileName = assetName
                        apkSize = size
                        Logger.i("Found matching APK asset: $assetName ($size bytes) -> $downloadUrl")
                        break
                    }
                }

                if (apkDownloadUrl.isNullOrEmpty() || apkFileName.isNullOrEmpty()) {
                    Logger.e("No .apk asset found in release $tagName (assets count: ${assets.length()})")
                    return@withContext Result.failure(IllegalStateException("No .apk asset found in release $tagName"))
                }

                val releaseInfo = ReleaseInfo(
                    tagName = tagName,
                    name = name,
                    publishedAt = publishedAt,
                    apkAssetUrl = apkDownloadUrl,
                    apkFileName = apkFileName,
                    apkSize = apkSize
                )
                Result.success(releaseInfo)
            }
        } catch (e: Exception) {
            Logger.e("Exception while fetching GitHub release", e)
            Result.failure(e)
        }
    }
}
