package com.cfox.droidmesh.installer

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.cfox.droidmesh.utils.Logger
import java.io.File
import java.security.MessageDigest
import java.util.Arrays

object ApkSignatureVerifier {

    /**
     * Verifies that:
     * 1. [apkFile] exists, is non-empty, and can be parsed as an Android package archive.
     * 2. The internal package name in [apkFile] matches [expectedPackageName].
     * 3. The APK is signed with valid certificates.
     * 4. If [expectedPackageName] is already installed on device, the signing certificate(s) of [apkFile]
     *    match the installed application's signing certificate(s).
     */
    fun verifyApk(
        context: Context,
        apkFile: File,
        expectedPackageName: String
    ): Result<Unit> {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            val msg = "APK file does not exist or is empty (${apkFile.absolutePath})"
            Logger.e(msg)
            return Result.failure(IllegalArgumentException(msg))
        }

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo: PackageInfo? = try {
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } catch (e: Exception) {
            Logger.e("Failed to parse package archive for ${apkFile.name}", e)
            null
        }

        if (archiveInfo == null) {
            val msg = "Cannot parse APK archive: corrupted or invalid APK file (${apkFile.name})"
            Logger.e(msg)
            return Result.failure(SecurityException(msg))
        }

        if (archiveInfo.packageName != expectedPackageName) {
            val msg = "APK package mismatch: expected '$expectedPackageName' but APK contains '${archiveInfo.packageName}'"
            Logger.e(msg)
            return Result.failure(SecurityException(msg))
        }

        val apkSignatures = extractSignatures(archiveInfo)
        if (apkSignatures.isEmpty()) {
            val msg = "APK signature verification failed: downloaded APK is unsigned or has no signatures"
            Logger.e(msg)
            return Result.failure(SecurityException(msg))
        }

        // Check if package is already installed
        val installedPkgInfo: PackageInfo? = try {
            pm.getPackageInfo(expectedPackageName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            Logger.w("Error reading installed package info for $expectedPackageName: ${e.message}")
            null
        }

        if (installedPkgInfo != null) {
            val installedSignatures = extractSignatures(installedPkgInfo)
            if (installedSignatures.isNotEmpty()) {
                val matches = signaturesMatch(installedSignatures, apkSignatures)
                if (!matches) {
                    val installedFingerprints = installedSignatures.map { getSha256Fingerprint(it) }
                    val apkFingerprints = apkSignatures.map { getSha256Fingerprint(it) }
                    val msg = "APK signature mismatch for $expectedPackageName: downloaded cert(s) $apkFingerprints do not match installed cert(s) $installedFingerprints"
                    Logger.e(msg)
                    return Result.failure(SecurityException(msg))
                }
            }
        }

        Logger.i("APK signature and package verification passed for $expectedPackageName (${apkFile.name})")
        return Result.success(Unit)
    }

    private fun extractSignatures(packageInfo: PackageInfo): List<Signature> {
        val sigs = mutableListOf<Signature>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo != null) {
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners?.let { sigs.addAll(it) }
                } else {
                    signingInfo.signingCertificateHistory?.let { sigs.addAll(it) }
                }
            }
        }
        if (sigs.isEmpty()) {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.let { sigs.addAll(it) }
        }
        return sigs
    }

    fun signaturesMatch(installed: List<Signature>, apk: List<Signature>): Boolean {
        for (inst in installed) {
            for (target in apk) {
                val instBytes = inst.toByteArray()
                val targetBytes = target.toByteArray()
                if (instBytes != null && targetBytes != null && instBytes.isNotEmpty() && targetBytes.isNotEmpty()) {
                    if (Arrays.equals(instBytes, targetBytes)) return true
                } else {
                    val instChars = try { inst.toCharsString() } catch (_: Exception) { null }
                    val targetChars = try { target.toCharsString() } catch (_: Exception) { null }
                    if (!instChars.isNullOrBlank() && !targetChars.isNullOrBlank()) {
                        if (instChars.equals(targetChars, ignoreCase = true)) return true
                    } else if (inst === target) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun getSha256Fingerprint(signature: Signature): String {
        return try {
            val bytes = signature.toByteArray()
            if (bytes != null && bytes.isNotEmpty()) {
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(bytes)
                digest.joinToString(":") { "%02X".format(it) }
            } else {
                try { signature.toCharsString()?.take(16) ?: "unknown" } catch (_: Exception) { "unknown" }
            }
        } catch (e: Exception) {
            try { signature.toCharsString()?.take(16) ?: "unknown" } catch (_: Exception) { "unknown" }
        }
    }
}
