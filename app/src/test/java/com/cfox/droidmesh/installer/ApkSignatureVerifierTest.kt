package com.cfox.droidmesh.installer

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class ApkSignatureVerifierTest {

    // [PROGRAMMATIC] INST-TEST-012: verifyApk succeeds when certificates match, fails when they differ
    @Test
    fun testVerifyApkWithMatchingCertificates() {
        val testApk = File.createTempFile("test_app", ".apk").apply {
            writeText("dummy apk content")
            deleteOnExit()
        }

        val mockPm: PackageManager = mock()
        val mockContext: Context = mock {
            whenever(it.packageManager).thenReturn(mockPm)
        }

        val matchingSignature = Signature("308201a1")

        val archivePkgInfo = PackageInfo().apply {
            packageName = "com.example.app"
            @Suppress("DEPRECATION")
            signatures = arrayOf(matchingSignature)
        }
        val installedPkgInfo = PackageInfo().apply {
            packageName = "com.example.app"
            @Suppress("DEPRECATION")
            signatures = arrayOf(matchingSignature)
        }

        whenever(mockPm.getPackageArchiveInfo(eq(testApk.absolutePath), any<Int>())).thenReturn(archivePkgInfo)
        whenever(mockPm.getPackageInfo(eq("com.example.app"), any<Int>())).thenReturn(installedPkgInfo)

        val matchResult = ApkSignatureVerifier.verifyApk(mockContext, testApk, "com.example.app")
        assertTrue("Matching signatures must succeed", matchResult.isSuccess)
    }

    // [PROGRAMMATIC] INST-TEST-012 (negative): verifyApk fails when signing certificates differ
    @Test
    fun testVerifyApkWithMismatchingCertificates() {
        val testApk = File.createTempFile("test_mismatch", ".apk").apply {
            writeText("dummy apk content")
            deleteOnExit()
        }

        val mockPm: PackageManager = mock()
        val mockContext: Context = mock {
            whenever(it.packageManager).thenReturn(mockPm)
        }

        val archiveSignature = Signature("308201a1")
        val differentSignature = Signature("308201b2")

        val archivePkgInfo = PackageInfo().apply {
            packageName = "com.example.app"
            @Suppress("DEPRECATION")
            signatures = arrayOf(archiveSignature)
        }
        val mismatchInstalledPkgInfo = PackageInfo().apply {
            packageName = "com.example.app"
            @Suppress("DEPRECATION")
            signatures = arrayOf(differentSignature)
        }

        whenever(mockPm.getPackageArchiveInfo(eq(testApk.absolutePath), any<Int>())).thenReturn(archivePkgInfo)
        whenever(mockPm.getPackageInfo(eq("com.example.app"), any<Int>())).thenReturn(mismatchInstalledPkgInfo)

        val mismatchResult = ApkSignatureVerifier.verifyApk(mockContext, testApk, "com.example.app")
        assertTrue("Mismatched signatures must fail", mismatchResult.isFailure)
        assertTrue(mismatchResult.exceptionOrNull() is SecurityException)
        assertTrue(mismatchResult.exceptionOrNull()?.message?.contains("signature mismatch") == true)
    }

    // [PROGRAMMATIC] INST-TEST-013: verifyApk rejects package mismatch, unsigned APK, and missing file
    @Test
    fun testVerifyApkRejectsPackageMismatchAndUnsignedApk() {
        val testApk = File.createTempFile("test_mismatch", ".apk").apply {
            writeText("dummy apk content")
            deleteOnExit()
        }

        val mockPm: PackageManager = mock()
        val mockContext: Context = mock {
            whenever(it.packageManager).thenReturn(mockPm)
        }

        val sig = Signature("308201a1")

        // 1. Package name mismatch
        val evilArchiveInfo = PackageInfo().apply {
            packageName = "com.attacker.evil"
            @Suppress("DEPRECATION")
            signatures = arrayOf(sig)
        }
        whenever(mockPm.getPackageArchiveInfo(eq(testApk.absolutePath), any<Int>())).thenReturn(evilArchiveInfo)

        val mismatchPkgResult = ApkSignatureVerifier.verifyApk(mockContext, testApk, "com.example.app")
        assertTrue("Package name mismatch must fail", mismatchPkgResult.isFailure)
        assertTrue(mismatchPkgResult.exceptionOrNull() is SecurityException)
        assertTrue(mismatchPkgResult.exceptionOrNull()?.message?.contains("package mismatch") == true)

        // 2. Unsigned APK
        val unsignedArchiveInfo = PackageInfo().apply {
            packageName = "com.example.app"
            @Suppress("DEPRECATION")
            signatures = emptyArray()
        }
        whenever(mockPm.getPackageArchiveInfo(eq(testApk.absolutePath), any<Int>())).thenReturn(unsignedArchiveInfo)

        val unsignedResult = ApkSignatureVerifier.verifyApk(mockContext, testApk, "com.example.app")
        assertTrue("Unsigned APK must fail", unsignedResult.isFailure)
        assertTrue(unsignedResult.exceptionOrNull() is SecurityException)

        // 3. Non-existent file
        val nonExistent = File("/tmp/does_not_exist_${System.currentTimeMillis()}.apk")
        val missingResult = ApkSignatureVerifier.verifyApk(mockContext, nonExistent, "com.example.app")
        assertTrue("Missing file must fail", missingResult.isFailure)
    }
}
