package com.cfox.droidmesh.installer

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import com.cfox.droidmesh.utils.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

/**
 * INST-BEHAVE-024: silent, prompt-free APK install for devices where DroidMesh has been
 * provisioned as Android Device Owner (`dpm set-device-owner`). A Device Owner app may commit a
 * `PackageInstaller.Session` without ever raising the system confirmation dialog
 * INST-BEHAVE-001/007 otherwise require -- no ADB loopback socket, no accessibility auto-click,
 * no interactive installer UI at all. `UpdateCoordinator` tries this first and only falls back to
 * the existing `AdbLoopbackInstaller` -> `PackageInstallerDispatcher` chain when DroidMesh is not
 * the registered Device Owner, or when a Device Owner install attempt itself fails.
 */
object DeviceOwnerInstaller {

    private const val ACTION_INSTALL_STATUS = "com.cfox.droidmesh.ACTION_DEVICE_OWNER_INSTALL_STATUS"

    /** True only when DroidMesh itself is the device's registered Device Owner app. */
    fun isDeviceOwner(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        dpm?.isDeviceOwnerApp(context.packageName) ?: false
    }.getOrDefault(false)

    /**
     * Streams [apkFile]'s full contents into an already-open install [session] and fsyncs the
     * write. Split out from [installSilently] so streaming is unit-testable against a mocked
     * `PackageInstaller.Session` without a live `PackageManager`.
     */
    internal fun streamApkIntoSession(session: PackageInstaller.Session, apkFile: File) {
        session.openWrite(apkFile.name, 0, apkFile.length()).use { out ->
            FileInputStream(apkFile).use { input -> input.copyTo(out) }
            session.fsync(out)
        }
    }

    /**
     * Maps a `PackageInstaller` commit status broadcast to a [Result]. Split out from the
     * `BroadcastReceiver` wiring in [installSilently] so status interpretation is unit-testable
     * without a real broadcast dispatch.
     */
    internal fun interpretCommitResult(status: Int, message: String?): Result<String> =
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Result.success(message ?: "")
        } else {
            Result.failure(
                IllegalStateException(
                    message?.takeIf { it.isNotBlank() } ?: "Device Owner install failed (status=$status)"
                )
            )
        }

    /**
     * Commits [apkFile] as [packageName] via a fresh full-install `PackageInstaller.Session` and
     * suspends until the commit's status broadcast reports success or failure. Returns
     * `Result.failure` immediately, before touching `PackageInstaller` at all, when DroidMesh is
     * not the Device Owner or the APK file is missing/empty.
     */
    suspend fun installSilently(context: Context, apkFile: File, packageName: String): Result<String> {
        if (!isDeviceOwner(context)) {
            return Result.failure(IllegalStateException("DroidMesh is not the Device Owner"))
        }
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return Result.failure(IllegalArgumentException("APK file invalid or empty"))
        }

        return suspendCancellableCoroutine { cont ->
            var receiver: BroadcastReceiver? = null
            var registered = false

            fun unregisterQuietly() {
                if (registered) {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (ignored: Exception) {
                    }
                }
            }

            try {
                val packageInstaller = context.packageManager.packageInstaller
                val sessionId = packageInstaller.createSession(
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                )
                val action = "$ACTION_INSTALL_STATUS.$sessionId"

                receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        unregisterQuietly()
                        val result = interpretCommitResult(status, message)
                        result.fold(
                            onSuccess = { Logger.i("Device Owner install succeeded for $packageName") },
                            onFailure = { Logger.e("Device Owner install failed for $packageName: ${it.message}") }
                        )
                        if (cont.isActive) cont.resume(result)
                    }
                }

                val filter = IntentFilter(action)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(receiver, filter)
                }
                registered = true

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(action).setPackage(context.packageName),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                )

                val session = packageInstaller.openSession(sessionId)
                session.use { s ->
                    streamApkIntoSession(s, apkFile)
                    s.commit(pendingIntent.intentSender)
                }
            } catch (e: Exception) {
                Logger.e("Device Owner install threw before commit", e)
                unregisterQuietly()
                if (cont.isActive) cont.resume(Result.failure(e))
                return@suspendCancellableCoroutine
            }

            cont.invokeOnCancellation { unregisterQuietly() }
        }
    }
}
