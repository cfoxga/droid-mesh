package com.cfox.droidmesh.downloader

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class ApkDownloaderTest {

    // [PROGRAMMATIC] UPD-TEST-013: ApkDownloader rejects cleartext HTTP and untrusted hosts
    @Test
    fun testDownloadRejectsCleartextAndUntrustedUrls() = runBlocking {
        val mockContext: Context = mock {
            whenever(it.filesDir).thenReturn(File("/tmp"))
        }
        val downloader = ApkDownloader(mockContext)

        // 1. Cleartext HTTP URL
        val cleartextResult = downloader.downloadApk("http://github.com/cfoxga/app.apk", "app.apk")
        assertTrue("Cleartext HTTP URL must be rejected", cleartextResult.isFailure)
        assertTrue(cleartextResult.exceptionOrNull() is SecurityException)

        // 2. Untrusted domain URL
        val untrustedResult = downloader.downloadApk("https://attacker.evil/malicious.apk", "malicious.apk")
        assertTrue("Untrusted host URL must be rejected", untrustedResult.isFailure)
        assertTrue(untrustedResult.exceptionOrNull() is SecurityException)
    }

    // [PROGRAMMATIC] UPD-TEST-015: ApkDownloader rejects unsafe filenames (shell metacharacters,
    // path traversal) even on an otherwise-trusted URL, before any network request is made.
    @Test
    fun testDownloadRejectsUnsafeFileNames() = runBlocking {
        val mockContext: Context = mock {
            whenever(it.filesDir).thenReturn(File("/tmp"))
        }
        val downloader = ApkDownloader(mockContext)
        val trustedUrl = "https://github.com/cfoxga/app/releases/download/v1/app.apk"

        // 1. Shell metacharacters (gitea#53's exact exploitation chain: filename flows unescaped
        // into AdbLoopbackInstaller's `cat "<path>" | pm install ...` shell string)
        val injectionResult = downloader.downloadApk(trustedUrl, "app.apk\"; touch /tmp/pwned; echo \"")
        assertTrue("Shell metacharacter filename must be rejected", injectionResult.isFailure)
        assertTrue(injectionResult.exceptionOrNull() is SecurityException)

        // 2. Path traversal via directory separators
        val traversalResult = downloader.downloadApk(trustedUrl, "../../../data/data/com.cfox.droidmesh/evil.apk")
        assertTrue("Path traversal filename must be rejected", traversalResult.isFailure)
        assertTrue(traversalResult.exceptionOrNull() is SecurityException)

        // 3. Bare ".." resolves to the parent directory via File(dir, "..") with no "/" present,
        // so the character whitelist alone (which allows ".") is not sufficient.
        val bareDotDotResult = downloader.downloadApk(trustedUrl, "..")
        assertTrue("Bare .. filename must be rejected", bareDotDotResult.isFailure)
        assertTrue(bareDotDotResult.exceptionOrNull() is SecurityException)

        // 4. A normal, safe filename is still accepted at the validation layer (it will go on to
        // attempt a real network request against github.com, so just assert it wasn't rejected as
        // unsafe -- i.e. no SecurityException naming an invalid filename).
        val safeResult = downloader.downloadApk("https://attacker.evil/x.apk", "app-release.apk")
        assertTrue(safeResult.isFailure)
        assertTrue("Safe filename must not itself be rejected -- failure here must be the host check", safeResult.exceptionOrNull() is SecurityException)
        assertTrue((safeResult.exceptionOrNull()?.message ?: "").contains("untrusted", ignoreCase = true))
    }
}
