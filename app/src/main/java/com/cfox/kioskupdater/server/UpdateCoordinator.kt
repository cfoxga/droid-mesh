package com.cfox.kioskupdater.server

import android.content.Context
import com.cfox.kioskupdater.api.GitHubReleaseApi
import com.cfox.kioskupdater.api.ReleaseInfo
import com.cfox.kioskupdater.api.UpdateStatus
import com.cfox.kioskupdater.api.VersionComparison
import com.cfox.kioskupdater.downloader.ApkDownloader
import com.cfox.kioskupdater.installer.AppVersionHelper
import com.cfox.kioskupdater.installer.PackageInstallerDispatcher
import com.cfox.kioskupdater.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun checkVersion(): Result<VersionComparison> {
        val installed = AppVersionHelper.getInstalledVersion(context)
        val releaseResult = releaseApi.fetchLatestRelease()

        return releaseResult.map { release ->
            val updateAvailable = AppVersionHelper.isUpdateAvailable(
                installed.versionName,
                release.tagName
            )
            VersionComparison(
                installedVersionName = installed.versionName,
                installedVersionCode = installed.versionCode,
                latestVersionTag = release.tagName,
                isUpdateAvailable = updateAvailable,
                releaseInfo = release
            )
        }
    }

    fun startUpdateAsync(force: Boolean = false, onComplete: (Result<ReleaseInfo>) -> Unit = {}) {
        scope.launch {
            val result = executeUpdateSequence(force)
            onComplete(result)
        }
    }

    suspend fun executeUpdateSequence(force: Boolean = false): Result<ReleaseInfo> {
        if (!updateMutex.tryLock()) {
            val busyMsg = "Update is already in progress"
            Logger.w(busyMsg)
            return Result.failure(IllegalStateException(busyMsg))
        }

        try {
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

            if (!comparison.isUpdateAvailable && !force) {
                val msg = "Installed version (${comparison.installedVersionName}) is already up to date with ${release.tagName}"
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

            // Installation Step
            _statusFlow.value = UpdateStatus(
                state = "INSTALLING",
                message = "Dispatching APK installer...",
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
