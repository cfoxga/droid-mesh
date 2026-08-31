package com.cfox.droidmesh.server

import android.content.Context
import com.cfox.droidmesh.api.GitHubReleaseApi
import com.cfox.droidmesh.api.ReleaseInfo
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

class UpdateCoordinator(
    private val context: Context,
    private val releaseApi: GitHubReleaseApi = GitHubReleaseApi(),
    private val downloader: ApkDownloader = ApkDownloader(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateMutex = Mutex()

    private val _statusFlow = MutableStateFlow(
        UpdateStatus(state = "IDLE", message = "Ready")
    )
    val statusFlow: StateFlow<UpdateStatus> = _statusFlow.asStateFlow()

    private var cachedReleases: List<ReleaseInfo> = emptyList()

    suspend fun fetchAvailableReleases(forceRefresh: Boolean = false): Result<List<ReleaseInfo>> {
        if (!forceRefresh && cachedReleases.isNotEmpty()) {
            return Result.success(cachedReleases)
        }
        val result = releaseApi.fetchReleases(count = 10)
        if (result.isSuccess) {
            cachedReleases = result.getOrThrow()
        }
        return result
    }

    fun getCachedReleases(): List<ReleaseInfo> = cachedReleases

    suspend fun checkVersion(): Result<VersionComparison> {
        val installed = AppVersionHelper.getInstalledVersion(context)
        val releaseResult = fetchAvailableReleases(forceRefresh = true)

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

    fun startUpdateAsync(force: Boolean = false, onComplete: (Result<ReleaseInfo>) -> Unit = {}) {
        scope.launch {
            val result = executeUpdateSequence(force)
            onComplete(result)
        }
    }

    fun startUpdateForRelease(release: ReleaseInfo, force: Boolean = true, onComplete: (Result<ReleaseInfo>) -> Unit = {}) {
        scope.launch {
            val result = executeUpdateForSpecificRelease(release, force)
            onComplete(result)
        }
    }

    suspend fun executeUpdateSequence(force: Boolean = false): Result<ReleaseInfo> {
        _statusFlow.value = UpdateStatus(state = "CHECKING", message = "Querying latest release from GitHub...")
        Logger.i("Starting update sequence (force=$force)")

        val versionCheck = checkVersion()
        if (versionCheck.isFailure) {
            val err = versionCheck.exceptionOrNull()?.message ?: "Failed to check version"
            _statusFlow.value = UpdateStatus(state = "ERROR", message = err, error = err)
            return Result.failure(versionCheck.exceptionOrNull() ?: Exception(err))
        }

        val comparison = versionCheck.getOrThrow()
        val release = comparison.releaseInfo

        return executeUpdateForSpecificRelease(release, force = force)
    }

    suspend fun executeUpdateForSpecificRelease(release: ReleaseInfo, force: Boolean = true): Result<ReleaseInfo> {
        if (!updateMutex.tryLock()) {
            val busyMsg = "Update is already in progress"
            Logger.w(busyMsg)
            return Result.failure(IllegalStateException(busyMsg))
        }

        try {
            val installed = AppVersionHelper.getInstalledVersion(context)
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
                targetFileName = "kiosk-satellite-${release.tagName}.apk"
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
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(AppVersionHelper.TARGET_PACKAGE)
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

                PackageInstallerDispatcher.dispatchUninstall(context)

                // Wait for uninstallation to complete (up to 25 seconds)
                var uninstalled = false
                for (i in 0 until 50) {
                    kotlinx.coroutines.delay(500)
                    if (!AppVersionHelper.getInstalledVersion(context).isInstalled) {
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
