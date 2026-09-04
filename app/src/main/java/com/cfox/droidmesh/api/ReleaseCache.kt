package com.cfox.droidmesh.api

import com.cfox.droidmesh.security.TrustedReleaseHosts
import com.cfox.droidmesh.utils.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-`downloadUrl` release-list cache with a TTL and a last-known-good fallback
 * (`UPD-BEHAVE-009`, `UPD-BEHAVE-010`).
 *
 * Exists because GitHub's unauthenticated REST limit is 60 requests/hour *per source IP*, and the
 * whole fleet shares one WAN IP. The previous single-slot cache both thrashed between App Library
 * entries and was bypassed entirely by an always-forcing checkVersion(), so advisory pollers burned
 * the fleet's entire hourly budget and every release fetch 403'd.
 *
 * Deliberately free of Android and network types so the policy is unit-testable on the JVM: the
 * caller supplies the clock (`nowMs`) and the upstream [fetch].
 */
class ReleaseCache(private val ttlMs: Long = DEFAULT_TTL_MS) {

    companion object {
        /**
         * One upstream fetch per URL per 30 minutes. Worst case across the 5-unit fleet is
         * 5 devices x (1 self-update + 1 per managed app) x 2 per hour, which stays well clear of
         * GitHub's 60 req/hour unauthenticated ceiling on the shared WAN IP. The Web UI's explicit
         * refresh button still forces a fetch, so this never blocks seeing a just-cut release.
         */
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L
    }

    private data class Entry(val releases: List<ReleaseInfo>, val fetchedAtMs: Long)

    private val entries = HashMap<String, Entry>()
    private val lock = Mutex()

    /**
     * Returns the release list for [url], fetching only when there is no entry younger than the
     * TTL — or when [forceRefresh] is set.
     *
     * On a fetch failure, the previously cached list for *this same* [url] is returned instead of
     * the failure, so a Target Version list that resolved once keeps working through a rate-limit
     * window. The failure propagates only when no prior successful fetch exists for that URL.
     *
     * `UPD-BEHAVE-016` / gitea#71 (defense-in-depth): every cache hit -- fresh or stale fallback
     * -- is re-validated against [TrustedReleaseHosts] before being served. An entry containing
     * any untrusted `apkAssetUrl` is evicted and treated as a miss rather than returned, so a
     * single poisoned write (e.g. from a gap in the `#57` host check) cannot keep serving spoofed
     * release data for the rest of its TTL.
     */
    suspend fun resolve(
        url: String,
        forceRefresh: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        fetch: suspend () -> Result<List<ReleaseInfo>>
    ): Result<List<ReleaseInfo>> {
        if (!forceRefresh) {
            val fresh = lock.withLock {
                val candidate = entries[url]?.takeIf { nowMs - it.fetchedAtMs < ttlMs }
                if (candidate != null && !isEntryTrusted(candidate)) {
                    evictPoisoned(url, candidate)
                    null
                } else {
                    candidate
                }
            }
            if (fresh != null) return Result.success(fresh.releases)
        }

        val result = fetch()
        if (result.isSuccess) {
            val releases = result.getOrThrow()
            lock.withLock { entries[url] = Entry(releases, nowMs) }
            return result
        }

        val stale = lock.withLock {
            val candidate = entries[url]
            if (candidate != null && !isEntryTrusted(candidate)) {
                evictPoisoned(url, candidate)
                null
            } else {
                candidate
            }
        }
        if (stale != null) {
            Logger.w(
                "Release fetch failed for $url (${result.exceptionOrNull()?.message}); " +
                    "serving ${stale.releases.size} cached release(s) from the last successful fetch"
            )
            return Result.success(stale.releases)
        }
        return result
    }

    /**
     * Last successfully fetched list for [url], regardless of age; empty if never fetched or if
     * the cached entry fails the [TrustedReleaseHosts] re-check (`gitea#71`, evicted as a miss).
     */
    suspend fun peek(url: String): List<ReleaseInfo> = lock.withLock {
        val candidate = entries[url]
        if (candidate != null && !isEntryTrusted(candidate)) {
            evictPoisoned(url, candidate)
            return@withLock emptyList()
        }
        candidate?.releases.orEmpty()
    }

    /** Must be called while already holding [lock]. */
    private fun isEntryTrusted(entry: Entry): Boolean =
        entry.releases.all { TrustedReleaseHosts.isTrustedReleaseUrl(it.apkAssetUrl) }

    /** Must be called while already holding [lock]. */
    private fun evictPoisoned(url: String, entry: Entry) {
        Logger.e(
            "Evicting cached release entry for $url: apkAssetUrl failed TrustedReleaseHosts " +
                "re-validation on read (${entry.releases.map { it.apkAssetUrl }})"
        )
        entries.remove(url)
    }
}
