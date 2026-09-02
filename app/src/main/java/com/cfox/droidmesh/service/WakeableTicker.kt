package com.cfox.droidmesh.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * FLT-BEHAVE-008: drives UpdaterForegroundService's hourly mesh auto-action loop so a config
 * change (an App Library edit — a new pinned targetVersion, autoUpdate/autoInstall flipped) can
 * wake it immediately instead of leaving it asleep for the rest of the current hour. No Android
 * dependencies — only kotlinx-coroutines — so it's unit-testable without mocking Service/Context.
 *
 * A CONFLATED channel means any number of wake() calls that land before the loop consumes them
 * collapse into a single early return, not one extra pass per SettingsStore write.
 */
class WakeableTicker(private val intervalMs: Long) {

    private val wakeSignal = Channel<Unit>(capacity = Channel.CONFLATED)

    /** Requests an early return from a currently-waiting (or next) awaitNextTick() call. */
    fun wake() {
        wakeSignal.trySend(Unit)
    }

    /** Suspends until intervalMs elapses or wake() is called, whichever happens first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitNextTick() {
        select<Unit> {
            wakeSignal.onReceive { }
            onTimeout(intervalMs) { }
        }
    }
}
