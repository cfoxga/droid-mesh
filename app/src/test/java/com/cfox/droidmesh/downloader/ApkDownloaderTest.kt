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
}
