package com.cfox.droidmesh.server

import android.content.Context
import com.cfox.droidmesh.api.GitHubReleaseFetcher
import com.cfox.droidmesh.downloader.ApkDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * [PROGRAMMATIC] UPD-TEST-016: `UpdateCoordinator.fetchAvailableReleases` must enforce
 * `TrustedReleaseHosts` on the incoming `downloadUrl` before any network call -- gitea#57 (SSRF).
 * The App Library `downloadUrl` field is writable via the unauthenticated
 * `/api/mesh/sync-config`/`/api/mesh/handshake` endpoints, so an unchecked fetch here is a fully
 * unauthenticated SSRF against arbitrary LAN/internal HTTPS hosts, reflected back into `/check`.
 */
class UpdateCoordinatorTest {

    private fun coordinator(): UpdateCoordinator {
        val mockContext: Context = mock()
        // A real GitHubReleaseFetcher/ApkDownloader are fine here -- for an untrusted URL, the
        // fix must reject before either is ever invoked, so no network mocking is needed to prove
        // the negative case. For the trusted-host positive case we just assert it is NOT rejected
        // at the host-check layer (any further failure must come from the real network call).
        return UpdateCoordinator(
            context = mockContext,
            githubFetcher = GitHubReleaseFetcher(),
            downloader = ApkDownloader(mockContext)
        )
    }

    // UPD-TEST-016 (negative): an untrusted-host downloadUrl is rejected before any fetch runs.
    @Test
    fun testFetchAvailableReleasesRejectsUntrustedDownloadUrl() = runBlocking {
        val result = coordinator().fetchAvailableReleases("https://attacker.evil/releases.json")

        assertTrue("untrusted downloadUrl must be rejected", result.isFailure)
        assertTrue(
            "rejection must be a SecurityException, not an uncaught throw",
            result.exceptionOrNull() is SecurityException
        )
    }

    // UPD-TEST-016 (negative): cleartext HTTP on an otherwise-trusted host is also rejected --
    // TrustedReleaseHosts.isTrustedReleaseUrl requires https://.
    @Test
    fun testFetchAvailableReleasesRejectsCleartextDownloadUrl() = runBlocking {
        val result = coordinator().fetchAvailableReleases("http://github.com/cfoxga/app/releases")

        assertTrue("cleartext downloadUrl must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    // UPD-TEST-016 (positive control): a trusted-host downloadUrl is NOT rejected by the host
    // check. To keep this test offline and deterministic (no real network call), the URL is a
    // trusted host that is neither a GitHub releases URL nor a direct .apk URL, so it reaches --
    // and fails at -- the coordinator's own "Unsupported download URL format" branch with zero
    // network calls made. Reaching that branch at all proves the host check let it through;
    // failing closed with the untrusted-host SecurityException instead would prove a regression.
    @Test
    fun testFetchAvailableReleasesDoesNotRejectTrustedDownloadUrlAtHostCheck() = runBlocking {
        val result = coordinator().fetchAvailableReleases("https://git.cfoxga.com/cfoxga/some-repo")

        assertTrue("expected a failure from the unsupported-format branch", result.isFailure)
        assertTrue(
            "trusted host must reach format parsing, not be rejected as untrusted",
            result.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            (result.exceptionOrNull()?.message ?: "").contains("Unsupported download URL format")
        )
    }
}
