package com.cfox.droidmesh.service

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FLT-TEST-007] WakeableTicker drives UpdaterForegroundService's hourly mesh auto-action loop
 * (FLT-BEHAVE-008): a libraryChanged config event must wake a waiting loop immediately instead
 * of leaving it asleep for up to the rest of AUTO_INSTALL_CHECK_MS.
 */
class WakeableTickerTest {

    // [PROGRAMMATIC] FLT-TEST-007: wake() returns awaitNextTick() immediately instead of waiting
    // out the full interval — this is what lets an App Library edit skip the rest of the hour.
    @Test
    fun testWakeReturnsBeforeIntervalElapses() = runBlocking {
        val ticker = WakeableTicker(intervalMs = 5_000L)
        val start = System.nanoTime()
        val waiter = async { ticker.awaitNextTick() }
        delay(50)
        ticker.wake()
        waiter.await()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "expected an early wake well under the 5s interval, took ${elapsedMs}ms",
            elapsedMs < 1000
        )
    }

    // [PROGRAMMATIC] FLT-TEST-007 (negative): no wake() call means the full interval is honored —
    // proves awaitNextTick() can't return early on its own.
    @Test
    fun testNoWakeWaitsOutTheFullInterval() = runBlocking {
        val ticker = WakeableTicker(intervalMs = 150L)
        val start = System.nanoTime()
        ticker.awaitNextTick()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "expected to wait out the interval, returned after ${elapsedMs}ms",
            elapsedMs >= 150
        )
    }

    // [PROGRAMMATIC] FLT-TEST-007: multiple wake() calls queued before the loop consumes them
    // collapse into a single early return, not one per SettingsStore write.
    @Test
    fun testMultipleQueuedWakesCollapseToOne() = runBlocking {
        val ticker = WakeableTicker(intervalMs = 300L)
        ticker.wake()
        ticker.wake()
        ticker.wake()

        val start1 = System.nanoTime()
        ticker.awaitNextTick() // consumes the coalesced signal, returns immediately
        val elapsed1Ms = (System.nanoTime() - start1) / 1_000_000
        assertTrue(
            "expected immediate return from the coalesced wake, took ${elapsed1Ms}ms",
            elapsed1Ms < 150
        )

        // If each wake() had queued its own signal instead of collapsing to one, this second
        // call would also return immediately. It must instead wait out the full interval.
        val start2 = System.nanoTime()
        ticker.awaitNextTick()
        val elapsed2Ms = (System.nanoTime() - start2) / 1_000_000
        assertTrue(
            "expected the second call to wait out the interval, returned after ${elapsed2Ms}ms",
            elapsed2Ms >= 300
        )
    }
}
