package com.cfox.droidmesh.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [PROGRAMMATIC] UPD-TEST-006 / UPD-TEST-007 / UPD-TEST-008: pure cache-policy coverage for
 * ReleaseCache.resolve() — no Android dependencies, so this runs as a plain JVM test.
 *
 * Regression origin: with a single global cache slot and a checkVersion() that always forced a
 * refresh, one open Web UI tab polling /api/self-update/status every 60s consumed GitHub's entire
 * 60 req/hour unauthenticated budget for the whole fleet's shared WAN IP. Every subsequent release
 * fetch 403'd, and the Target Version dropdown collapsed to a bare "latest".
 */
class ReleaseCacheTest {

    // UPD-TEST-018 / gitea#71: apkAssetUrl must be a TrustedReleaseHosts host now that
    // resolve()/peek() re-validate on read -- example.com is not on the allowlist, so every
    // pre-existing fixture in this file moved to a trusted host to keep testing cache *policy*
    // (TTL, per-URL isolation, stale fallback) independently of the new host-trust behavior,
    // which gets its own dedicated tests below with a deliberately untrusted URL.
    private fun rel(tag: String) = ReleaseInfo(
        tagName = tag,
        name = tag,
        publishedAt = "",
        apkAssetUrl = "https://github.com/owner/repo/releases/download/$tag/app-$tag.apk",
        apkFileName = "app-$tag.apk",
        apkSize = 0L
    )

    private fun poisonedRel(tag: String) = ReleaseInfo(
        tagName = tag,
        name = tag,
        publishedAt = "",
        apkAssetUrl = "https://attacker.evil/app-$tag.apk",
        apkFileName = "app-$tag.apk",
        apkSize = 0L
    )

    private val urlA = "https://api.github.com/repos/owner/a/releases"
    private val urlB = "https://api.github.com/repos/owner/b/releases"

    // UPD-TEST-006: inside the TTL, a repeat call is served from cache with no upstream fetch.
    @Test
    fun testSecondCallInsideTtlServesCacheWithoutFetching() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)
        var fetches = 0

        val first = cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            fetches++
            Result.success(listOf(rel("1.0.0")))
        }
        val second = cache.resolve(urlA, forceRefresh = false, nowMs = 400_000L) {
            fetches++
            Result.success(listOf(rel("2.0.0")))
        }

        assertEquals(1, fetches)
        assertEquals(listOf("1.0.0"), first.getOrThrow().map { it.tagName })
        assertEquals(listOf("1.0.0"), second.getOrThrow().map { it.tagName })
    }

    // UPD-TEST-006: distinct URLs get distinct cache entries — no mutual eviction.
    @Test
    fun testDistinctUrlsDoNotEvictEachOther() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)
        var fetches = 0

        cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            fetches++; Result.success(listOf(rel("1.0.0")))
        }
        cache.resolve(urlB, forceRefresh = false, nowMs = 2_000L) {
            fetches++; Result.success(listOf(rel("9.9.9")))
        }
        // urlA must still be cached after urlB was fetched.
        val backToA = cache.resolve(urlA, forceRefresh = false, nowMs = 3_000L) {
            fetches++; Result.success(listOf(rel("bogus")))
        }

        assertEquals(2, fetches)
        assertEquals(listOf("1.0.0"), backToA.getOrThrow().map { it.tagName })
    }

    // UPD-TEST-007 (negative): past the TTL the fetch runs again.
    @Test
    fun testExpiredTtlRefetches() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)
        var fetches = 0

        cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            fetches++; Result.success(listOf(rel("1.0.0")))
        }
        val after = cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L + 900_001L) {
            fetches++; Result.success(listOf(rel("2.0.0")))
        }

        assertEquals(2, fetches)
        assertEquals(listOf("2.0.0"), after.getOrThrow().map { it.tagName })
    }

    // UPD-TEST-007 (negative): forceRefresh bypasses a still-fresh entry.
    @Test
    fun testForceRefreshBypassesFreshEntry() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)
        var fetches = 0

        cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            fetches++; Result.success(listOf(rel("1.0.0")))
        }
        val forced = cache.resolve(urlA, forceRefresh = true, nowMs = 2_000L) {
            fetches++; Result.success(listOf(rel("2.0.0")))
        }

        assertEquals(2, fetches)
        assertEquals(listOf("2.0.0"), forced.getOrThrow().map { it.tagName })
    }

    // UPD-TEST-008: a 403 after a prior success serves the last-known-good list.
    @Test
    fun testFailureAfterSuccessServesStaleInsteadOfFailing() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)

        cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            Result.success(listOf(rel("1.0.0"), rel("0.9.0")))
        }
        val duringOutage = cache.resolve(urlA, forceRefresh = true, nowMs = 5_000_000L) {
            Result.failure(IOException("GitHub API responded with code 403"))
        }

        assertTrue("expected stale-serve, got ${duringOutage.exceptionOrNull()}", duringOutage.isSuccess)
        assertEquals(listOf("1.0.0", "0.9.0"), duringOutage.getOrThrow().map { it.tagName })
    }

    // UPD-TEST-008 (negative): with no prior success there is nothing to serve — fail loudly.
    @Test
    fun testFailureWithNoPriorSuccessPropagates() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)

        val result = cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            Result.failure(IOException("GitHub API responded with code 403"))
        }

        assertTrue("expected failure to propagate", result.isFailure)
        assertEquals("GitHub API responded with code 403", result.exceptionOrNull()?.message)
    }

    // UPD-TEST-008: peek() exposes the last known-good list without a fetch, for the /check
    // partial-response path; it is empty for a URL that never resolved.
    @Test
    fun testPeekReturnsLastKnownGoodWithoutFetching() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)
        assertEquals(emptyList<String>(), cache.peek(urlA).map { it.tagName })

        cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            Result.success(listOf(rel("1.0.0")))
        }
        // Far past the TTL — peek ignores age by design.
        assertEquals(listOf("1.0.0"), cache.peek(urlA).map { it.tagName })
        assertEquals(emptyList<String>(), cache.peek(urlB).map { it.tagName })
    }

    // UPD-TEST-008 (negative): a stale entry for a *different* URL must not rescue this one.
    @Test
    fun testStaleFallbackIsPerUrl() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)

        cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            Result.success(listOf(rel("1.0.0")))
        }
        val bFails = cache.resolve(urlB, forceRefresh = false, nowMs = 2_000L) {
            Result.failure(IOException("GitHub API responded with code 403"))
        }

        assertTrue("urlB has no prior success; must not borrow urlA's cache", bFails.isFailure)
    }

    // [PROGRAMMATIC] UPD-TEST-018 (negative): gitea#71 -- a fresh (within-TTL) cache entry whose
    // apkAssetUrl fails TrustedReleaseHosts must be treated as a miss, not served, and the fetch
    // lambda must run instead of being skipped.
    @Test
    fun testPoisonedEntryIsEvictedAndTreatedAsMissWithinTtl() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)
        var fetches = 0

        // Simulates a pre-#57-fix poisoned write: an untrusted apkAssetUrl made it into the cache.
        cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            fetches++; Result.success(listOf(poisonedRel("1.0.0")))
        }

        val second = cache.resolve(urlA, forceRefresh = false, nowMs = 2_000L) {
            fetches++; Result.success(listOf(rel("2.0.0")))
        }

        assertEquals(
            "poisoned entry must not be served from cache -- a second fetch must run",
            2, fetches
        )
        assertEquals(listOf("2.0.0"), second.getOrThrow().map { it.tagName })
    }

    // [PROGRAMMATIC] UPD-TEST-018 (negative): a poisoned entry must not be served as the
    // last-known-good stale fallback either -- a subsequent fetch failure with only a poisoned
    // entry on record must propagate the failure, not return spoofed data.
    @Test
    fun testPoisonedEntryIsNotServedAsStaleFallback() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)

        cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            Result.success(listOf(poisonedRel("1.0.0")))
        }
        val duringOutage = cache.resolve(urlA, forceRefresh = true, nowMs = 5_000_000L) {
            Result.failure(IOException("GitHub API responded with code 403"))
        }

        assertTrue(
            "a poisoned stale entry must not be served -- the outage failure must propagate",
            duringOutage.isFailure
        )
    }

    // [PROGRAMMATIC] UPD-TEST-018 (negative): peek() must not return a poisoned entry either.
    @Test
    fun testPeekEvictsAndHidesPoisonedEntry() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)

        cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            Result.success(listOf(poisonedRel("1.0.0")))
        }

        assertEquals(
            "peek() must treat a poisoned entry as absent",
            emptyList<String>(),
            cache.peek(urlA).map { it.tagName }
        )
    }

    // [PROGRAMMATIC] UPD-TEST-018 (negative): a single untrusted apkAssetUrl anywhere in an
    // entry's release list poisons the whole entry -- a mixed list (one trusted, one untrusted
    // release) must still be evicted/treated as a miss. Guards against an `.any { trusted }`
    // check that would wrongly let the entry through because *some* release passed.
    @Test
    fun testMixedTrustedAndPoisonedReleasesInOneEntryStillEvicted() = runBlocking {
        val cache = ReleaseCache(ttlMs = 900_000L)

        cache.resolve(urlA, forceRefresh = false, nowMs = 1_000L) {
            Result.success(listOf(rel("1.0.0"), poisonedRel("0.9.0")))
        }

        assertEquals(
            "an entry with any untrusted release must be evicted entirely, not partially trusted",
            emptyList<String>(),
            cache.peek(urlA).map { it.tagName }
        )
    }
}
