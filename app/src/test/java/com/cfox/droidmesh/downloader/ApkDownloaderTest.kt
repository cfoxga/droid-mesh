package com.cfox.droidmesh.downloader

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class ApkDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Fake OkHttp client that never touches the network: an interceptor short-circuits every
     * request against a fixed URL->response map, so redirect-chain handling can be exercised
     * deterministically and offline. [followRedirects]/[followSslRedirects] are left false to
     * match the production client built in gitea#64's fix -- ApkDownloader must do its own
     * redirect walking, not rely on OkHttp's.
     */
    private fun clientWithRoutes(routes: Map<String, (Request) -> Response>): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val req = chain.request()
                val handler = routes[req.url.toString()]
                    ?: return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found (unexpected route in test: ${req.url})")
                        .body("".toResponseBody(null))
                        .build()
                handler(req)
            }
            .build()

    private fun redirectResponse(req: Request, code: Int, location: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Redirect")
            .header("Location", location)
            .body("".toResponseBody(null))
            .build()

    private fun okResponse(req: Request, content: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(content.toResponseBody("application/octet-stream".toMediaType()))
            .build()

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

    // [PROGRAMMATIC] UPD-TEST-017 (negative): gitea#64 -- a trusted host redirecting to an
    // untrusted/cleartext host must fail the download, and the untrusted target must never
    // actually be queried (proves the redirect target is re-checked, not just the original URL).
    @Test
    fun testDownloadRejectsRedirectToUntrustedHost() = runBlocking {
        var untrustedHostQueried = false
        val trustedUrl = "https://github.com/cfoxga/app/releases/download/v1/app.apk"
        val client = clientWithRoutes(mapOf(
            trustedUrl to { req -> redirectResponse(req, 302, "https://attacker.evil/app.apk") },
            "https://attacker.evil/app.apk" to { req ->
                untrustedHostQueried = true
                okResponse(req, "evil-bytes")
            }
        ))
        val mockContext: Context = mock { whenever(it.filesDir).thenReturn(tempFolder.root) }
        val downloader = ApkDownloader(mockContext, client)

        val result = downloader.downloadApk(trustedUrl, "app.apk")

        assertTrue("redirect to an untrusted host must fail the download", result.isFailure)
        assertTrue(
            "rejection must be a SecurityException naming the redirect, not a generic IOException",
            result.exceptionOrNull() is SecurityException
        )
        assertFalse(
            "the untrusted redirect target must never actually be queried",
            untrustedHostQueried
        )
    }

    // [PROGRAMMATIC] UPD-TEST-017 (negative): a cleartext http:// redirect target must also be
    // rejected even though the initiating host and scheme were trusted/HTTPS -- OkHttp's
    // followSslRedirects(true) previously permitted exactly this https->http downgrade.
    @Test
    fun testDownloadRejectsRedirectToCleartextHttp() = runBlocking {
        val trustedUrl = "https://github.com/cfoxga/app/releases/download/v1/app.apk"
        val client = clientWithRoutes(mapOf(
            trustedUrl to { req -> redirectResponse(req, 302, "http://github.com/cfoxga/app.apk") }
        ))
        val mockContext: Context = mock { whenever(it.filesDir).thenReturn(tempFolder.root) }
        val downloader = ApkDownloader(mockContext, client)

        val result = downloader.downloadApk(trustedUrl, "app.apk")

        assertTrue("https-to-http downgrade redirect must fail the download", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    // [PROGRAMMATIC] UPD-TEST-017: a redirect chain that stays within trusted hosts on every hop
    // is followed and the download completes successfully.
    @Test
    fun testDownloadFollowsRedirectChainWithinTrustedHosts() = runBlocking {
        val startUrl = "https://github.com/cfoxga/app/releases/download/v1/app.apk"
        val hop2Url = "https://objects.githubusercontent.com/redirect-hop-2"
        val finalUrl = "https://git.cfoxga.com/cfoxga/app/final.apk"
        val client = clientWithRoutes(mapOf(
            startUrl to { req -> redirectResponse(req, 302, hop2Url) },
            hop2Url to { req -> redirectResponse(req, 302, finalUrl) },
            finalUrl to { req -> okResponse(req, "REAL-APK-BYTES") }
        ))
        val mockContext: Context = mock { whenever(it.filesDir).thenReturn(tempFolder.root) }
        val downloader = ApkDownloader(mockContext, client)

        val result = downloader.downloadApk(startUrl, "app-chain.apk")

        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
        val file = result.getOrThrow()
        assertEquals("REAL-APK-BYTES", file.readText())
    }

    // [PROGRAMMATIC] UPD-TEST-017: exactly 5 trusted-host hops (the documented cap) still
    // succeeds -- distinguishes "the cap rejected this" from "a redirect is never followed".
    @Test
    fun testDownloadSucceedsAtExactlyFiveHops() = runBlocking {
        val routes = HashMap<String, (Request) -> Response>()
        for (i in 0..4) {
            val from = "https://github.com/cfoxga/app/hop$i.apk"
            val to = "https://github.com/cfoxga/app/hop${i + 1}.apk"
            routes[from] = { req -> redirectResponse(req, 302, to) }
        }
        routes["https://github.com/cfoxga/app/hop5.apk"] = { req -> okResponse(req, "FIVE-HOPS-OK") }
        val client = clientWithRoutes(routes)
        val mockContext: Context = mock { whenever(it.filesDir).thenReturn(tempFolder.root) }
        val downloader = ApkDownloader(mockContext, client)

        val result = downloader.downloadApk("https://github.com/cfoxga/app/hop0.apk", "app-5hop.apk")

        assertTrue("exactly 5 hops (the documented cap) must still succeed, got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("FIVE-HOPS-OK", result.getOrThrow().readText())
    }

    // [PROGRAMMATIC] UPD-TEST-017 (negative): a redirect chain exceeding the hop cap fails the
    // download rather than looping indefinitely, and fails specifically because of the cap (not
    // merely because "a 3xx was returned") -- every hop here is otherwise a fully trusted host.
    @Test
    fun testDownloadFailsWhenRedirectChainExceedsHopCap() = runBlocking {
        val startUrl = "https://github.com/cfoxga/app/hop0.apk"
        // 7 trusted hops in a row -- one more than the documented cap (5) -- with no route ever
        // resolving to a 200, so the ONLY way this can fail is the cap itself firing.
        val routes = HashMap<String, (Request) -> Response>()
        for (i in 0..6) {
            val from = "https://github.com/cfoxga/app/hop$i.apk"
            val to = "https://github.com/cfoxga/app/hop${i + 1}.apk"
            routes[from] = { req -> redirectResponse(req, 302, to) }
        }
        val client = clientWithRoutes(routes)
        val mockContext: Context = mock { whenever(it.filesDir).thenReturn(tempFolder.root) }
        val downloader = ApkDownloader(mockContext, client)

        val result = downloader.downloadApk(startUrl, "app.apk")

        assertTrue("a redirect chain exceeding the hop cap must fail the download", result.isFailure)
        assertFalse(
            "must not be misreported as a host-trust rejection -- every hop here is trusted",
            result.exceptionOrNull() is SecurityException
        )
        assertTrue(
            "failure must explicitly name the redirect hop cap, not just 'got a 3xx': ${result.exceptionOrNull()?.message}",
            (result.exceptionOrNull()?.message ?: "").contains("redirect", ignoreCase = true) &&
                (result.exceptionOrNull()?.message ?: "").contains("hop", ignoreCase = true)
        )
    }
}
