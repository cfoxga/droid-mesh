package com.cfox.kiosksatelliteupdater.api

import com.cfox.kiosksatelliteupdater.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
        val releasesResult = fetchReleases(count = 1)
        releasesResult.mapCatching { releases ->
            releases.firstOrNull() ?: throw IllegalStateException("No releases found on GitHub")
        }
    }

    suspend fun fetchReleases(count: Int = 10): Result<List<ReleaseInfo>> = withContext(Dispatchers.IO) {
        // Request up to max(count * 2, 15) to account for draft/pre-releases without APKs
        val queryLimit = maxOf(count * 2, 15)
        val url = "https://api.github.com/repos/$repoOwner/$repoName/releases?per_page=$queryLimit"
        Logger.i("Fetching releases list from $url")

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
                val jsonArray = JSONArray(bodyString)
                val releases = mutableListOf<ReleaseInfo>()

                for (i in 0 until jsonArray.length()) {
                    val json = jsonArray.getJSONObject(i)
                    val tagName = json.optString("tag_name", "").trim()
                    val name = json.optString("name", tagName).trim()
                    val publishedAt = json.optString("published_at", "")
                    val isDraft = json.optBoolean("draft", false)
                    if (isDraft || tagName.isEmpty()) continue

                    val assets = json.optJSONArray("assets") ?: continue

                    var apkDownloadUrl: String? = null
                    var apkFileName: String? = null
                    var apkSize: Long = 0L

                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val assetName = asset.optString("name", "")
                        val downloadUrl = asset.optString("browser_download_url", "")
                        val size = asset.optLong("size", 0L)

                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = downloadUrl
                            apkFileName = assetName
                            apkSize = size
                            break
                        }
                    }

                    if (!apkDownloadUrl.isNullOrEmpty() && !apkFileName.isNullOrEmpty()) {
                        releases.add(
                            ReleaseInfo(
                                tagName = tagName,
                                name = if (name.isNotEmpty()) name else tagName,
                                publishedAt = publishedAt,
                                apkAssetUrl = apkDownloadUrl,
                                apkFileName = apkFileName,
                                apkSize = apkSize
                            )
                        )
                        if (releases.size >= count) break
                    }
                }

                if (releases.isEmpty()) {
                    Logger.w("No valid releases with APK assets found on GitHub")
                    return@withContext Result.failure(IllegalStateException("No releases with APK assets found"))
                }

                Logger.i("Successfully retrieved ${releases.size} releases (latest: ${releases.first().tagName})")
                Result.success(releases)
            }
        } catch (e: Exception) {
            Logger.e("Exception while fetching GitHub releases", e)
            Result.failure(e)
        }
    }
}
