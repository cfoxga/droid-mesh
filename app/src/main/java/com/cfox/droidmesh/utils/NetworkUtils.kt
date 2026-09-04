package com.cfox.droidmesh.utils

/**
 * Small shared network helpers with no Android framework dependency, so they're usable from both
 * production code and plain JUnit tests (mirrors the java.util.Base64 rationale in
 * AdbLoopbackInstaller — android.* equivalents are unavailable/unstubbed under the JVM test runner).
 */
object NetworkUtils {

    // SET-BEHAVE-010 / API-TEST-018: shared bind-probe extracted from
    // LocalHttpServer.isPortAvailable so the same check gates a candidate `web_server_port` on
    // both the authenticated POST /api/settings path and the unauthenticated mesh-sync path
    // (SettingsStore.importConfigJson) -- previously only the former checked bind availability at
    // all. Best-effort: briefly opens and immediately closes a socket on the candidate port to
    // confirm nothing else on-device already holds it. Racy in theory (TOCTOU against a process
    // binding between probe and actual rebind), but that race exists on the read side of any bind
    // check; it converts the common case (a stale/wrong/already-bound port) from a silent brick
    // into an explicit rejection up front.
    fun isPortAvailable(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
}
