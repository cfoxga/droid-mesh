package com.cfox.droidmesh.server

import android.content.Context
import com.cfox.droidmesh.api.GitHubReleaseFetcher
import com.cfox.droidmesh.api.ReleaseInfo
import com.cfox.droidmesh.api.ReleaseCache
import com.cfox.droidmesh.api.ReleaseParser
import com.cfox.droidmesh.api.ReleaseSelector
import com.cfox.droidmesh.api.UpdateStatus
import com.cfox.droidmesh.api.VersionComparison
import com.cfox.droidmesh.downloader.ApkDownloader
import com.cfox.droidmesh.installer.AdbLoopbackInstaller
import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.installer.PackageInstallerDispatcher
import com.cfox.droidmesh.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Coordinates update checking and installation for any app with a release source URL.
 *
 * Supports:
 * - GitHub releases API endpoints: https://api.github.com/repos/owner/repo/releases
 * - Direct APK download URLs: https://example.com/app-1.2.3.apk
 *
 * No app-specific hardcoding: every method requires an explicit packageName and downloadUrl,
 * both sourced from the caller's resolved App Library entry — there is no default target app
 * or default release URL anywhere in this class.
 */
class UpdateCoordinator(
    private val context: Context,
    private val githubFetcher: GitHubReleaseFetcher = GitHubReleaseFetcher(),
    private val downloader: ApkDownloader = ApkDownloader(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateMutex = Mutex()

    private val _statusFlow = MutableStateFlow(
        UpdateStatus(state = "IDLE", message = "Ready")
    )
    val statusFlow: StateFlow<UpdateStatus> = _statusFlow.asStateFlow()

    private val releaseCache = ReleaseCache()

    /**
     * Resolves the release list for [downloadUrl], served from [releaseCache] when a fetch is not
     * warranted (`UPD-BEHAVE-009`) and falling back to the last known-good list when the upstream
     * fetch fails (`UPD-BEHAVE-010`).
     */
    suspend fun fetchAvailableReleases(
        downloadUrl: String,
        forceRefresh: Boolean = false
    ): Result<List<ReleaseInfo>> = releaseCache.resolve(downloadUrl, forceRefresh) {
        // UPD-BEHAVE-008: accept the plain github.com URL an admin actually copies out of their
        // browser, not just the api.github.com form.
        val resolvedUrl = ReleaseParser.toGitHubApiUrl(downloadUrl) ?: downloadUrl

        when {
            ReleaseParser.isGitHubReleaseUrl(resolvedUrl) ->
                githubFetcher.fetchReleases(resolvedUrl, count = 10)
            ReleaseParser.isDirectApkUrl(resolvedUrl) -> {
                val release = ReleaseParser.parseDirectApkUrl(resolvedUrl)
                if (release != null) Result.success(listOf(release))
                else Result.failure(IllegalArgumentException("Invalid APK URL: $downloadUrl"))
            }
            else ->
                Result.failure(IllegalArgumentException("Unsupported download URL format: $downloadUrl"))
        }
    }

    /** Last successfully fetched releases for [downloadUrl] — no network, never fails. */
    suspend fun getCachedReleases(downloadUrl: String): List<ReleaseInfo> = releaseCache.peek(downloadUrl)

    /**
     * Resolves [downloadUrl] to the release matching [targetVersion] (`UPD-BEHAVE-012`), or the
     * newest release when [targetVersion] is `latest`/blank.
     */
    suspend fun resolveTargetRelease(
        downloadUrl: String,
        targetVersion: String?
    ): Result<ReleaseInfo> = fetchAvailableReleases(downloadUrl).mapCatching { releases ->
        ReleaseSelector.selectRelease(releases, targetVersion)
            ?: throw IllegalStateException(
                "No release matching target version '${targetVersion?.trim().orEmpty()}' at $downloadUrl"
            )
    }

    /**
     * `forceRefresh` defaults to false so advisory pollers ride the cache (`UPD-BEHAVE-011`) —
     * this used to force unconditionally, which is what exhausted GitHub's hourly limit.
     */
    suspend fun checkVersion(
        packageName: String,
        downloadUrl: String,
        forceRefresh: Boolean = false
    ): Result<VersionComparison> {
        val installed = AppVersionHelper.getInstalledVersion(context, packageName)
        val releaseResult = fetchAvailableReleases(downloadUrl, forceRefresh = forceRefresh)

        return releaseResult.map { releases ->
            val latest = releases.first()
            val updateAvailable = AppVersionHelper.isUpdateAvailable(
                installed.versionName,
                latest.tagName
            )
            VersionComparison(
                installedVersionName = installed.versionName,
                installedVersionCode = installed.versionCode,
                latestVersionTag = latest.tagName,
                isUpdateAvailable = updateAvailable,
                releaseInfo = latest
            )
        }
    }

    fun startUpdateAsync(
        packageName: String,
        downloadUrl: String,
        force: Boolean = false,
        onComplete: (Result<ReleaseInfo>) -> Unit = {}
    ) {
        scope.launch {
            val result = executeUpdateSequence(packageName, downloadUrl, force)
            onComplete(result)
        }
    }

    fun startUpdateForRelease(
        packageName: String,
        release: ReleaseInfo,
        force: Boolean = true,
        onComplete: (Result<ReleaseInfo>) -> Unit = {}
    ) {
        scope.launch {
            val result = executeUpdateForSpecificRelease(packageName, release, force)
            onComplete(result)
        }
    }

    suspend fun executeUpdateSequence(
        packageName: String,
        downloadUrl: String,
        force: Boolean = false
    ): Result<ReleaseInfo> {
        _statusFlow.value = UpdateStatus(state = "CHECKING", message = "Checking for updates...")
        Logger.i("Starting update sequence for $packageName from $downloadUrl (force=$force)")

        // Explicit, user-initiated update: pay for fresh data. A 403 here still falls back to
        // the cached list via ReleaseCache, so forcing cannot make this fail closed.
        val versionCheck = checkVersion(packageName, downloadUrl, forceRefresh = true)
        if (versionCheck.isFailure) {
            val err = versionCheck.exceptionOrNull()?.message ?: "Failed to check version"
            _statusFlow.value = UpdateStatus(state = "ERROR", message = err, error = err)
            return Result.failure(versionCheck.exceptionOrNull() ?: Exception(err))
        }

        val comparison = versionCheck.getOrThrow()
        val release = comparison.releaseInfo

        return executeUpdateForSpecificRelease(packageName, release, force = force)
    }

    suspend fun executeUpdateForSpecificRelease(packageName: String, release: ReleaseInfo, force: Boolean = true): Result<ReleaseInfo> {
        if (!updateMutex.tryLock()) {
            val busyMsg = "Update is already in progress"
            Logger.w(busyMsg)
            return Result.failure(IllegalStateException(busyMsg))
        }

        try {
            val installed = AppVersionHelper.getInstalledVersion(context, packageName)
            val updateAvailable = AppVersionHelper.isUpdateAvailable(
                installed.versionName,
                release.tagName
            )

            if (!updateAvailable && !force) {
                val msg = "Installed version (${installed.versionName}) is already up to date with ${release.tagName}"
                Logger.i(msg)
                _statusFlow.value = UpdateStatus(state = "IDLE", message = msg)
                return Result.success(release)
            }

            // Downloader Step
            _statusFlow.value = UpdateStatus(
                state = "DOWNLOADING",
                message = "Downloading ${release.apkFileName} (${release.tagName})...",
                totalBytes = release.apkSize
            )

            val downloadResult = downloader.downloadApk(
                downloadUrl = release.apkAssetUrl,
                targetFileName = release.apkFileName
            ) { progress ->
                _statusFlow.value = UpdateStatus(
                    state = "DOWNLOADING",
                    message = "Downloading: ${progress.progressPercent}% (${progress.bytesRead / 1024} KB)",
                    progressPercent = progress.progressPercent,
                    downloadedBytes = progress.bytesRead,
                    totalBytes = progress.totalBytes
                )
            }

            if (downloadResult.isFailure) {
                val err = downloadResult.exceptionOrNull()?.message ?: "Download failed"
                _statusFlow.value = UpdateStatus(state = "ERROR", message = err, error = err)
                return Result.failure(downloadResult.exceptionOrNull() ?: Exception(err))
            }

            val apkFile = downloadResult.getOrThrow()

            // 1. Try local loopback ADB installer first (works seamlessly for upgrades and downgrades with -r -d)
            _statusFlow.value = UpdateStatus(
                state = "INSTALLING",
                message = "Installing ${release.tagName}...",
                progressPercent = 100
            )

            val adbResult = AdbLoopbackInstaller.installWithAdbLoopback(apkFile)
            if (adbResult.isSuccess) {
                val successMsg = "Installed ${release.tagName} via local ADB with data preserved."
                Logger.i(successMsg)
                _statusFlow.value = UpdateStatus(
                    state = "COMPLETED",
                    message = successMsg,
                    progressPercent = 100
                )
                // Relaunch app
                try {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    launchIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (launchIntent != null) context.startActivity(launchIntent)
                } catch (ignored: Exception) {}

                return Result.success(release)
            }

            Logger.i("Local ADB loopback not available or failed; using PackageInstaller dispatcher")

            // 2. Package Installer fallback: Check if this is a downgrade
            val apkVersionCode = AppVersionHelper.getApkVersionCode(context, apkFile)
            val isDowngrade = installed.isInstalled && AppVersionHelper.isDowngrade(installed.versionCode, apkVersionCode)

            if (isDowngrade) {
                Logger.w("Downgrade detected: target APK build $apkVersionCode is older than installed ${installed.versionCode}. Uninstalling current version first.")
                _statusFlow.value = UpdateStatus(
                    state = "INSTALLING",
                    message = "Downgrading to ${release.tagName}: Uninstalling current version...",
                    progressPercent = 100
                )

                PackageInstallerDispatcher.dispatchUninstall(context, packageName)

                // Wait for uninstallation to complete (up to 25 seconds)
                var uninstalled = false
                for (i in 0 until 50) {
                    kotlinx.coroutines.delay(500)
                    if (!AppVersionHelper.getInstalledVersion(context, packageName).isInstalled) {
                        uninstalled = true
                        break
                    }
                }

                if (!uninstalled) {
                    val err = "Downgrade failed: uninstallation of previous version was not completed"
                    Logger.e(err)
                    _statusFlow.value = UpdateStatus(state = "ERROR", message = err, error = err)
                    return Result.failure(IllegalStateException(err))
                }

                Logger.i("Uninstalled previous version successfully. Now installing ${release.tagName}...")
                kotlinx.coroutines.delay(1000)
            }

            // Installation Step
            _statusFlow.value = UpdateStatus(
                state = "INSTALLING",
                message = "Dispatching APK installer for ${release.tagName}...",
                progressPercent = 100
            )

            com.cfox.droidmesh.service.AutoInstallService.pendingInstallPackage = packageName
            val installResult = PackageInstallerDispatcher.dispatchInstall(context, apkFile)
            if (installResult.isFailure) {
                val err = installResult.exceptionOrNull()?.message ?: "Failed to launch installer"
                _statusFlow.value = UpdateStatus(state = "ERROR", message = err, error = err)
                return Result.failure(installResult.exceptionOrNull() ?: Exception(err))
            }

            val successMsg = "Installer dispatched for ${release.tagName}. Accessibility service will handle installation dialogs."
            Logger.i(successMsg)
            _statusFlow.value = UpdateStatus(
                state = "COMPLETED",
                message = successMsg,
                progressPercent = 100
            )

            return Result.success(release)
        } finally {
            updateMutex.unlock()
        }
    }
}
