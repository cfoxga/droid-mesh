package com.cfox.droidmesh.server

import android.content.Context
import android.content.SharedPreferences
import com.cfox.droidmesh.api.ReleaseInfo

import com.cfox.droidmesh.settings.SettingsStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

class LocalHttpServerTest {

    private val inMemoryPrefs = mutableMapOf<String, Any>()
    private lateinit var mockContext: Context
    private lateinit var mockCoordinator: UpdateCoordinator
    private lateinit var mockSelfUpdateCoordinator: UpdateCoordinator
    private lateinit var server: LocalHttpServer

    @Before
    fun setUp() {
        inMemoryPrefs.clear()

        val editor: SharedPreferences.Editor = mock {
            whenever(it.putString(any(), anyOrNull())).thenAnswer { inv ->
                val k = inv.getArgument<String>(0)
                val v = inv.getArgument<String?>(1)
                if (v != null) inMemoryPrefs[k] = v else inMemoryPrefs.remove(k)
                it
            }
            whenever(it.putStringSet(any(), anyOrNull())).thenAnswer { inv ->
                val k = inv.getArgument<String>(0)
                val v = inv.getArgument<Set<String>?>(1)
                if (v != null) inMemoryPrefs[k] = v else inMemoryPrefs.remove(k)
                it
            }
            whenever(it.putBoolean(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] = inv.getArgument<Boolean>(1)
                it
            }
            whenever(it.putInt(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] = inv.getArgument<Int>(1)
                it
            }
            whenever(it.putLong(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] = inv.getArgument<Long>(1)
                it
            }
            whenever(it.remove(any())).thenAnswer { inv ->
                inMemoryPrefs.remove(inv.getArgument<String>(0))
                it
            }
            whenever(it.apply()).thenAnswer { }
            whenever(it.commit()).thenAnswer { true }
        }

        val sharedPrefs: SharedPreferences = mock {
            whenever(it.getBoolean(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] as? Boolean ?: inv.getArgument<Boolean>(1)
            }
            whenever(it.getInt(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] as? Int ?: inv.getArgument<Int>(1)
            }
            whenever(it.getLong(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] as? Long ?: inv.getArgument<Long>(1)
            }
            whenever(it.getString(any(), anyOrNull())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] as? String ?: inv.getArgument<String?>(1)
            }
            whenever(it.getStringSet(any(), anyOrNull())).thenAnswer { inv ->
                @Suppress("UNCHECKED_CAST")
                inMemoryPrefs[inv.getArgument<String>(0)] as? Set<String> ?: inv.getArgument<Set<String>?>(1) ?: emptySet<String>()
            }
            whenever(it.edit()).thenAnswer { editor }
        }

        val mockAssetManager: android.content.res.AssetManager = mock {
            whenever(it.open("web/index.html")).thenAnswer {
                ByteArrayInputStream("<!DOCTYPE html><html><body>Test UI</body></html>".toByteArray(Charsets.UTF_8))
            }
        }

        mockContext = mock {
            whenever(it.getSharedPreferences(any(), any())).thenAnswer { sharedPrefs }
            whenever(it.packageName).thenReturn("com.cfox.droidmesh")
            whenever(it.packageManager).thenReturn(mock())
            whenever(it.contentResolver).thenReturn(mock())
            whenever(it.assets).thenReturn(mockAssetManager)
        }

        mockCoordinator = mock {
            whenever(it.statusFlow).thenReturn(
                kotlinx.coroutines.flow.MutableStateFlow(
                    com.cfox.droidmesh.api.UpdateStatus(state = "IDLE", message = "Ready")
                )
            )
            // Resolution-layer tests only care whether handleCheck/handleUpdate reach the
            // coordinator at all (i.e. resolution succeeded) — not coordinator business logic —
            // so stub deterministic failures rather than leaving these suspend calls unstubbed.
            onBlocking { it.fetchAvailableReleases(any(), any()) } doReturn
                Result.failure(IllegalStateException("stubbed: no network in test"))
            onBlocking { it.checkVersion(any(), any(), any()) } doReturn
                Result.failure(IllegalStateException("stubbed: no network in test"))
            // Non-null return type: an unstubbed suspend mock hands back null and trips Kotlin's
            // null-check intrinsic, so stub the real class's "nothing cached yet" answer.
            onBlocking { it.getCachedReleases(any()) } doReturn emptyList()
        }

        // Dedicated coordinator for self-update, mirroring the production wiring in
        // UpdaterForegroundService — never shared with mockCoordinator (the managed-app one)
        // so API-TEST-012 can assert self-update never touches the managed-app coordinator.
        mockSelfUpdateCoordinator = mock {
            whenever(it.statusFlow).thenReturn(
                kotlinx.coroutines.flow.MutableStateFlow(
                    com.cfox.droidmesh.api.UpdateStatus(state = "IDLE", message = "Ready")
                )
            )
            onBlocking { it.checkVersion(any(), any(), any()) } doReturn
                Result.failure(IllegalStateException("stubbed: no network in test"))
        }

        server = LocalHttpServer(
            context = mockContext,
            coordinator = mockCoordinator,
            meshManager = null,
            activePort = 2325,
            selfUpdateCoordinator = mockSelfUpdateCoordinator
        )

    }

    private fun mockSession(
        uri: String,
        method: NanoHTTPD.Method = NanoHTTPD.Method.GET,
        headers: Map<String, String> = emptyMap(),
        params: Map<String, String> = emptyMap(),
        postBody: String? = null
    ): NanoHTTPD.IHTTPSession {
        val cleanUri = if (uri.contains("?")) uri.substringBefore("?") else uri
        val queryParams = params.toMutableMap()
        if (uri.contains("?")) {
            val queryStr = uri.substringAfter("?")
            queryStr.split("&").forEach {
                val kv = it.split("=")
                if (kv.size == 2) queryParams[kv[0]] = kv[1]
            }
        }
        val mutableHeaders = headers.toMutableMap()
        val bodyBytes = postBody?.toByteArray(Charsets.UTF_8)
        if (bodyBytes != null) {
            mutableHeaders["content-length"] = bodyBytes.size.toString()
        }
        val cookies = server.CookieHandler(mutableHeaders)
        return mock {
            whenever(it.uri).thenReturn(cleanUri)
            whenever(it.method).thenReturn(method)
            whenever(it.headers).thenReturn(mutableHeaders)
            whenever(it.parms).thenReturn(queryParams)
            whenever(it.cookies).thenReturn(cookies)
            whenever(it.remoteIpAddress).thenReturn("192.168.40.100")
            if (bodyBytes != null) {
                whenever(it.inputStream).thenAnswer { ByteArrayInputStream(bodyBytes) }
            }
        }
    }



    // [PROGRAMMATIC] API-TEST-001: LocalHttpServer status endpoint
    @Test
    fun testStatusEndpointReturnsOk() {
        val session = mockSession("/api/status")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("application/json; charset=utf-8", response.mimeType)
        val bodyStr = response.data?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        val json = JSONObject(bodyStr)
        assertEquals("DroidMesh", json.getString("app"))
        assertTrue(json.has("version"))
    }

    @Test
    fun testAuthStatusWhenNoPassword() {
        val session = mockSession("/api/auth/status")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    // [PROGRAMMATIC] API-TEST-003: Protected endpoints and settings configuration
    // [PROGRAMMATIC] API-TEST-004: Token generation and auth login
    @Test
    fun testLoginAndProtectedEndpoints() {
        // Set password
        SettingsStore.setPassword(mockContext, "secret123")

        // 1. Unauthenticated request to /api/settings (POST) should fail with 401
        val unauthSession = mockSession(
            uri = "/api/settings",
            method = NanoHTTPD.Method.POST,
            postBody = """{"webServerPort": 2330}"""
        )
        val unauthResponse = server.serve(unauthSession)
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, unauthResponse.status)

        // 2. Login with wrong password should fail
        val badLoginSession = mockSession(
            uri = "/api/login",
            method = NanoHTTPD.Method.POST,
            postBody = """{"password": "wrong"}"""
        )
        val badLoginResponse = server.serve(badLoginSession)
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, badLoginResponse.status)

        // 3. Login with correct password should return token
        val goodLoginSession = mockSession(
            uri = "/api/login",
            method = NanoHTTPD.Method.POST,
            postBody = """{"password": "secret123"}"""
        )
        val goodLoginResponse = server.serve(goodLoginSession)
        assertEquals(NanoHTTPD.Response.Status.OK, goodLoginResponse.status)

        // 4. Authenticated request with Bearer token should succeed
        val validToken = SettingsStore.generateToken(mockContext)
        val authedSession = mockSession(
            uri = "/api/settings",
            method = NanoHTTPD.Method.POST,
            headers = mapOf("authorization" to "Bearer $validToken"),
            postBody = """{"webServerPort": 2330}"""
        )
        val authedResponse = server.serve(authedSession)
        assertEquals(NanoHTTPD.Response.Status.OK, authedResponse.status)
        assertEquals(2330, SettingsStore.getWebServerPort(mockContext))
    }

    // [PROGRAMMATIC] API-TEST-017: out-of-range webServerPort is rejected server-side, not just
    // client-side — the local UI depends on this server, so an invalid value must never persist
    @Test
    fun testSettingsRejectsOutOfRangePort() {
        val session = mockSession(
            uri = "/api/settings",
            method = NanoHTTPD.Method.POST,
            postBody = """{"webServerPort": 80}"""
        )
        val before = SettingsStore.getWebServerPort(mockContext)
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        assertEquals(before, SettingsStore.getWebServerPort(mockContext))
    }

    // [PROGRAMMATIC] API-TEST-018: a syntactically valid but already-bound webServerPort is
    // rejected, not silently accepted and applied later by a foreground service that can't rebind
    @Test
    fun testSettingsRejectsAlreadyBoundPort() {
        val busySocket = java.net.ServerSocket(0)
        try {
            val busyPort = busySocket.localPort
            val session = mockSession(
                uri = "/api/settings",
                method = NanoHTTPD.Method.POST,
                postBody = """{"webServerPort": $busyPort}"""
            )
            val before = SettingsStore.getWebServerPort(mockContext)
            val response = server.serve(session)
            assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
            assertEquals(before, SettingsStore.getWebServerPort(mockContext))
        } finally {
            busySocket.close()
        }
    }

    // [PROGRAMMATIC] API-TEST-019: re-posting the server's own current port is never rejected as
    // "already bound", even though this process itself effectively holds it
    @Test
    fun testSettingsAllowsReapplyingCurrentActivePort() {
        val session = mockSession(
            uri = "/api/settings",
            method = NanoHTTPD.Method.POST,
            postBody = """{"webServerPort": ${server.activePort}}"""
        )
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    // [PROGRAMMATIC] API-TEST-002: Mesh endpoint response
    @Test
    fun testMeshEndpointReturnsOk() {
        val session = mockSession("/api/mesh")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("application/json; charset=utf-8", response.mimeType)
    }

    // [PROGRAMMATIC] API-TEST-027: bare /mesh must reach the JSON handler, not the SPA shell —
    // it was permanently shadowed by the earlier "/" || "/index.html" || ... "/mesh" || "/logs"
    // static-web-shell branch, which always wins in a Kotlin `when` (first match, no fallthrough).
    @Test
    fun testBareMeshEndpointReturnsJsonNotWebShell() {
        val session = mockSession("/mesh")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("application/json; charset=utf-8", response.mimeType)
    }

    // [PROGRAMMATIC] API-TEST-028: bare /logs must reach the JSON handler, not the SPA shell —
    // same shadowing bug as testBareMeshEndpointReturnsJsonNotWebShell, for the /logs alias.
    @Test
    fun testBareLogsEndpointReturnsJsonNotWebShell() {
        val session = mockSession("/logs")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("application/json; charset=utf-8", response.mimeType)
        val body = response.data.bufferedReader().readText()
        assertTrue(JSONObject(body).has("logs"))
    }

    // [PROGRAMMATIC] MESH-TEST-003: Mesh seeds endpoints
    @Test
    fun testMeshSeedsEndpoints() {
        // GET seeds
        val getSession = mockSession("/api/mesh/seeds")
        val getRes = server.serve(getSession)
        assertEquals(NanoHTTPD.Response.Status.OK, getRes.status)

        // Seed Removal (DELETE)
        SettingsStore.addPersistentConnection(mockContext, "192.168.50.10:2325")
        val deleteSession = mockSession(
            uri = "/api/mesh/seeds",
            method = NanoHTTPD.Method.DELETE,
            postBody = """{"ip": "192.168.50.10:2325"}"""
        )
        val deleteRes = server.serve(deleteSession)
        assertEquals(NanoHTTPD.Response.Status.OK, deleteRes.status)
    }

    @Test
    fun testMeshHandshakeEndpoint() {
        val handshakeSession = mockSession(
            uri = "/api/mesh/handshake",
            method = NanoHTTPD.Method.POST,
            postBody = """{"sender_ip": "192.168.50.10", "sender_port": 2325, "mesh_id": "googletv", "mesh_name": "Google TV", "reciprocal": false}"""
        )
        val response = server.serve(handshakeSession)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertTrue(SettingsStore.getPersistentConnections(mockContext).contains("192.168.50.10:2325"))
    }

    @Test
    fun testMeshConfigGetAndSyncEndpoints() {
        // [API-BEHAVE-027] With a real password set locally, GET /api/mesh/config must never leak
        // auth_secret/web_password_hash/web_password_salt -- gitea#31, live-fleet-confirmed leak.
        SettingsStore.setPassword(mockContext, "localAdminPassword")
        SettingsStore.generateToken(mockContext)

        // 1. Initial GET config
        val getSession = mockSession("/api/mesh/config")
        val getRes = server.serve(getSession)
        assertEquals(NanoHTTPD.Response.Status.OK, getRes.status)
        val getJson = JSONObject(getRes.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        val getConfig = getJson.getJSONObject("config")
        assertFalse(getConfig.has("auth_secret"))
        assertFalse(getConfig.has("web_password_hash"))
        assertFalse(getConfig.has("web_password_salt"))

        // 2. Incoming newer config via /api/mesh/sync-config
        val syncPayload = JSONObject().apply {
            put("config_version", 2000000000000L)
            put("web_server_enabled", true)
            put("web_server_port", 2326)
            put("web_password_hash", "testhash123")
            put("web_password_salt", "testsalt123")
            put("auth_secret", "testsecret123")
            val seedsArr = org.json.JSONArray().apply {
                put("192.168.40.250:2326")
                put("192.168.50.64:2326")
            }
            put("cross_vlan_seeds", seedsArr)
        }

        val syncSession = mockSession(
            uri = "/api/mesh/sync-config",
            method = NanoHTTPD.Method.POST,
            postBody = syncPayload.toString()
        )
        val syncRes = server.serve(syncSession)
        assertEquals(NanoHTTPD.Response.Status.OK, syncRes.status)

        // Verify that SettingsStore reflects the synced values -- except credential fields, which
        // [SET-BEHAVE-007] never applies from an incoming sync payload (gitea#32: this same payload
        // shape, unauthenticated, previously let a remote caller overwrite the local admin password
        // with its own attacker-supplied hash/secret and have it mesh-propagate). The real local
        // password set at the top of this test must survive the sync completely unchanged.
        assertEquals(2326, SettingsStore.getWebServerPort(mockContext))
        assertTrue(SettingsStore.isPasswordSet(mockContext))
        assertTrue(SettingsStore.verifyPassword(mockContext, "localAdminPassword"))
        assertTrue(SettingsStore.getPersistentConnections(mockContext).contains("192.168.50.64:2326"))
        assertEquals(2000000000000L, SettingsStore.getConfigVersion(mockContext))

        // 3. A subsequent GET must still never leak credentials -- a leak introduced only on the
        // post-sync code path would have escaped the pre-sync-only check above.
        val getAfterSyncSession = mockSession("/api/mesh/config")
        val getAfterSyncRes = server.serve(getAfterSyncSession)
        assertEquals(NanoHTTPD.Response.Status.OK, getAfterSyncRes.status)
        val getAfterSyncConfig = JSONObject(
            getAfterSyncRes.data?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        ).getJSONObject("config")
        assertFalse(getAfterSyncConfig.has("auth_secret"))
        assertFalse(getAfterSyncConfig.has("web_password_hash"))
        assertFalse(getAfterSyncConfig.has("web_password_salt"))
    }

    // [PROGRAMMATIC] API-TEST-005: Mesh Library GET and POST endpoints
    @Test
    fun testMeshLibraryGetAndPostEndpoints() {
        // 1. GET initial library
        val getSession = mockSession("/api/mesh/library?meshId=meta-portals")
        val getRes = server.serve(getSession)
        assertEquals(NanoHTTPD.Response.Status.OK, getRes.status)

        // 2. POST update app config, including the required downloadUrl
        val postPayload = JSONObject().apply {
            put("meshId", "meta-portals")
            put("packageName", "com.cfoxga.mpttv")
            put("appName", "MPT TV")
            put("managed", true)
            put("autoInstall", true)
            put("targetVersion", "latest")
            put("autoUpdate", true)
            put("isSideloaded", true)
            put("downloadUrl", "https://example.com/releases/mpttv.apk")
        }
        val postSession = mockSession(
            uri = "/api/mesh/library",
            method = NanoHTTPD.Method.POST,
            postBody = postPayload.toString()
        )
        val postRes = server.serve(postSession)
        assertEquals(NanoHTTPD.Response.Status.OK, postRes.status)

        val lib = SettingsStore.getMeshAppLibrary(mockContext, "meta-portals")
        assertTrue(lib["com.cfoxga.mpttv"]?.autoInstall == true)
        assertTrue("managed should persist when downloadUrl is present", lib["com.cfoxga.mpttv"]?.managed == true)
    }

    // [PROGRAMMATIC] API-TEST-006 (SET-BEHAVE-005 regression via HTTP): POSTing managed=true with
    // no downloadUrl must coerce to managed=false rather than persisting an unusable entry.
    @Test
    fun testMeshLibraryPostCoercesManagedFalseWithoutDownloadUrl() {
        val postPayload = JSONObject().apply {
            put("meshId", "meta-portals")
            put("packageName", "com.cfoxga.nodownloadurl")
            put("appName", "No URL App")
            put("managed", true)
            put("isSideloaded", true)
        }
        val postSession = mockSession(
            uri = "/api/mesh/library",
            method = NanoHTTPD.Method.POST,
            postBody = postPayload.toString()
        )
        val postRes = server.serve(postSession)
        assertEquals(NanoHTTPD.Response.Status.OK, postRes.status)

        val lib = SettingsStore.getMeshAppLibrary(mockContext, "meta-portals")
        assertFalse("managed must coerce to false with no downloadUrl", lib["com.cfoxga.nodownloadurl"]?.managed == true)
    }

    // [PROGRAMMATIC] API-TEST-020: /status no longer reports any singleton-target-app fields —
    // the old resolveTargetApp() mechanism (API-BEHAVE-014/015, deprecated) is gone entirely.
    @Test
    fun testStatusHasNoTargetAppFields() {
        val session = mockSession("/api/status")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertFalse(json.has("targetPackage"))
        assertFalse(json.has("managedCandidates"))
        assertFalse(json.has("targetInstalled"))
        assertFalse(json.has("installedVersionName"))
        assertFalse(json.has("installedVersionCode"))
    }

    // [PROGRAMMATIC] API-TEST-021: /check with no ?package= returns 400 "package is required",
    // regardless of whether any App Library entry exists or is managed.
    @Test
    fun testCheckWithoutPackageReturns400() {
        val session = mockSession("/api/check")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertTrue(json.getString("message").contains("package is required", ignoreCase = true))
    }

    // [PROGRAMMATIC] API-TEST-022: /check?package=<unknown> (not in the local mesh's App
    // Library) returns 400 naming the package.
    @Test
    fun testCheckWithUnknownPackageReturns400() {
        val session = mockSession("/api/check?package=com.example.unknown")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertTrue(json.getString("message").contains("com.example.unknown"))
    }

    // [PROGRAMMATIC] API-TEST-023: /check?package=<pkg> with a blank downloadUrl returns 400
    // naming the package.
    @Test
    fun testCheckWithBlankDownloadUrlReturns400() {
        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.nodownload",
                appName = "No Download",
                managed = false,
                downloadUrl = ""
            )
        )
        val session = mockSession("/api/check?package=com.example.nodownload")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertTrue(json.getString("message").contains("com.example.nodownload"))
    }

    // [PROGRAMMATIC] UI-TEST-009: peer-card drift detection must resolve what `targetVersion`
    // points at before comparing. `isVersionMismatch` hard-returns false for the literal "latest",
    // which is every entry's default, so comparing the raw field made `needsUpdate` permanently
    // false and the per-node Update button never rendered no matter how far a peer had drifted.
    @Test
    fun testPeerCardsResolveLatestBeforeComparingVersions() {
        val assetFile = java.io.File("src/main/assets/web/index.html")
        assertTrue("index.html asset must exist", assetFile.exists())
        val html = assetFile.readText()

        assertTrue(
            "index.html must define resolveTargetTag()",
            html.contains("function resolveTargetTag(")
        )
        assertTrue(
            "index.html must define peerAppNeedsUpdate()",
            html.contains("function peerAppNeedsUpdate(")
        )
        // Negative case: no peer-card path may compare the raw targetVersion field any more.
        assertEquals(
            "peer cards must not compare the raw cfg.targetVersion",
            0,
            Regex("isVersionMismatch\\(\\s*app\\.versionName\\s*,\\s*cfg\\.targetVersion")
                .findAll(html).count()
        )
        // The Update button must hand the peer the *resolved* tag, not the literal "latest".
        assertEquals(
            "the peer Update button must pass a resolved tag to triggerAppUpdate",
            0,
            Regex("triggerAppUpdate\\([^)]*cfg\\.targetVersion").findAll(html).count()
        )
        assertTrue(
            "the peer Update button must pass resolveTargetTag(cfg)",
            html.contains("triggerAppUpdate") && html.contains("resolveTargetTag(cfg)")
        )
        // Resolution is useless if the release list never arrives on the Mesh Nodes tab, so pin
        // the two pieces of wiring that make it arrive and take effect.
        assertTrue(
            "renderMeshPeers must lazily kick off loadReleasesForPackage for managed entries",
            html.contains("loadReleasesForPackage(cfg.packageName, state.selectedMeshId)")
        )
        assertTrue(
            "loadReleasesForPackage must repaint the whole mesh view when the list arrives",
            html.contains("renderCurrentMeshView()")
        )
    }

    // [PROGRAMMATIC] API-TEST-030: /update with a `tag` but no explicit `url` must resolve that
    // tag against the entry's downloadUrl and install THAT release. It previously fell through to
    // the plain "install latest" path, so a peer Update button for a pinned version silently
    // installed the newest build instead (API-BEHAVE-021).
    @Test
    fun testUpdateWithTagButNoUrlInstallsThatSpecificRelease() = runBlocking {
        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.pinned",
                appName = "Pinned",
                managed = true,
                downloadUrl = "https://github.com/owner/pinned/releases"
            )
        )
        val pinned = ReleaseInfo(
            tagName = "v1.5.0", name = "v1.5.0", publishedAt = "",
            apkAssetUrl = "https://example.com/app-1.5.0.apk",
            apkFileName = "app-1.5.0.apk", apkSize = 5L
        )
        whenever(mockCoordinator.resolveTargetRelease(any(), any())).thenReturn(Result.success(pinned))

        val response = server.serve(
            mockSession("/api/update?package=com.example.pinned&tag=v1.5.0", NanoHTTPD.Method.POST)
        )

        assertEquals(NanoHTTPD.Response.Status.ACCEPTED, response.status)
        verify(mockCoordinator).startUpdateForRelease(
            eq("com.example.pinned"), eq(pinned), eq(false), any()
        )
        // Must NOT have taken the "just install latest" path.
        verify(mockCoordinator, never()).startUpdateAsync(any(), any(), any(), any())
    }

    // [PROGRAMMATIC] API-TEST-030 (negative): a tag that matches no release is a 400 naming the
    // tag, never a silent fall-back to installing latest.
    @Test
    fun testUpdateWithUnresolvableTagReturns400AndInstallsNothing() = runBlocking {
        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.badpin",
                appName = "Bad Pin",
                managed = true,
                downloadUrl = "https://github.com/owner/badpin/releases"
            )
        )
        whenever(mockCoordinator.resolveTargetRelease(any(), any())).thenReturn(
            Result.failure(IllegalStateException("No release matching target version 'v9.9.9'"))
        )

        val response = server.serve(
            mockSession("/api/update?package=com.example.badpin&tag=v9.9.9", NanoHTTPD.Method.POST)
        )

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertTrue(json.getString("message").contains("v9.9.9"))
        verify(mockCoordinator, never()).startUpdateForRelease(any(), any(), any(), any())
        verify(mockCoordinator, never()).startUpdateAsync(any(), any(), any(), any())
    }

    // [PROGRAMMATIC] API-TEST-029: /check must still hand back the release list when only the
    // version *comparison* failed. Regression: the old handler returned a bare 500 with no
    // `releases` key in that case, so the Web UI's Target Version selector fell back to a lone
    // "latest" option (UPD-BEHAVE-010).
    @Test
    fun testCheckReturnsReleasesWhenOnlyVersionComparisonFails() = runBlocking {
        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.partial",
                appName = "Partial",
                managed = true,
                downloadUrl = "https://github.com/owner/partial/releases"
            )
        )
        whenever(mockCoordinator.fetchAvailableReleases(any(), any())).thenReturn(
            Result.success(
                listOf(
                    com.cfox.droidmesh.api.ReleaseInfo(
                        tagName = "v2.0.0", name = "v2.0.0", publishedAt = "",
                        apkAssetUrl = "https://example.com/app-2.0.0.apk",
                        apkFileName = "app-2.0.0.apk", apkSize = 10L
                    ),
                    com.cfox.droidmesh.api.ReleaseInfo(
                        tagName = "v1.0.0", name = "v1.0.0", publishedAt = "",
                        apkAssetUrl = "https://example.com/app-1.0.0.apk",
                        apkFileName = "app-1.0.0.apk", apkSize = 10L
                    )
                )
            )
        )
        // checkVersion stays stubbed as a failure by setUp().

        val response = server.serve(mockSession("/api/check?package=com.example.partial"))
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("partial", json.getString("status"))
        val tags = (0 until json.getJSONArray("releases").length())
            .map { json.getJSONArray("releases").getJSONObject(it).getString("tagName") }
        assertEquals(listOf("v2.0.0", "v1.0.0"), tags)
    }

    // [PROGRAMMATIC] API-TEST-029 (negative): when the release fetch fails too and nothing is
    // cached, /check must still fail loudly rather than pretend success with an empty list.
    @Test
    fun testCheckStillReturns500WhenNoReleasesAvailableAtAll() {
        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.nothing",
                appName = "Nothing",
                managed = true,
                downloadUrl = "https://github.com/owner/nothing/releases"
            )
        )
        // Both fetchAvailableReleases and checkVersion stay stubbed as failures by setUp().
        val response = server.serve(mockSession("/api/check?package=com.example.nothing"))
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")

        assertEquals(NanoHTTPD.Response.Status.INTERNAL_ERROR, response.status)
        assertEquals("error", json.getString("status"))
        assertEquals(0, json.getJSONArray("releases").length())
    }

    // [PROGRAMMATIC] API-TEST-024: two App Library entries simultaneously managed=true, each
    // resolved independently via explicit ?package= with no ambiguity error — proves the
    // singleton "one managed app" resolver (API-BEHAVE-014, deprecated) is gone.
    @Test
    fun testCheckResolvesEitherOfMultipleSimultaneouslyManagedApps() {
        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.appone",
                appName = "App One",
                managed = true,
                downloadUrl = "https://example.com/appone.apk"
            )
        )
        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.apptwo",
                appName = "App Two",
                managed = true,
                downloadUrl = "https://example.com/apptwo.apk"
            )
        )

        val oneResponse = server.serve(mockSession("/api/check?package=com.example.appone"))
        assertNotEquals(NanoHTTPD.Response.Status.BAD_REQUEST, oneResponse.status)
        val twoResponse = server.serve(mockSession("/api/check?package=com.example.apptwo"))
        assertNotEquals(NanoHTTPD.Response.Status.BAD_REQUEST, twoResponse.status)
    }

    // [PROGRAMMATIC] API-TEST-025: /update with no package (query or body) returns 400
    // "package is required".
    @Test
    fun testUpdateWithoutPackageReturns400() {
        val session = mockSession("/api/update", method = NanoHTTPD.Method.POST, postBody = "{}")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertTrue(json.getString("message").contains("package is required", ignoreCase = true))
    }

    // [PROGRAMMATIC] API-TEST-026: /update?package=<unknown> returns 400 naming the package.
    @Test
    fun testUpdateWithUnknownPackageReturns400() {
        val session = mockSession(
            "/api/update?package=com.example.unknown",
            method = NanoHTTPD.Method.POST,
            postBody = "{}"
        )
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertTrue(json.getString("message").contains("com.example.unknown"))
    }

    // [PROGRAMMATIC] MESH-TEST-008: Deleting "unmanaged" is rejected (negative case for MESH-BEHAVE-009)
    @Test
    fun testMeshDeleteRejectsUnmanaged() {
        val session = mockSession(
            uri = "/api/mesh/delete",
            method = NanoHTTPD.Method.POST,
            postBody = """{"meshId": "unmanaged"}"""
        )
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        assertTrue(SettingsStore.getKnownMeshes(mockContext).any { it.id == "unmanaged" })
    }

    // [PROGRAMMATIC] MESH-TEST-009: Deleting a mesh with peer_count > 0 is rejected with 400
    @Test
    fun testMeshDeleteRejectsWhenPeersAssigned() {
        SettingsStore.addKnownMesh(mockContext, "googletv", "Google TV")

        val peer = com.cfox.droidmesh.mesh.PeerNode(
            id = "device-1",
            ip = "192.168.50.64",
            meshId = "googletv",
            meshName = "Google TV"
        )
        val mockMeshManager: com.cfox.droidmesh.mesh.MeshDiscoveryManager = mock {
            whenever(it.peersFlow).thenReturn(
                kotlinx.coroutines.flow.MutableStateFlow(listOf(peer))
            )
        }
        val serverWithPeers = LocalHttpServer(
            context = mockContext,
            coordinator = mockCoordinator,
            meshManager = mockMeshManager,
            activePort = 2325
        )

        val session = mockSession(
            uri = "/api/mesh/delete",
            method = NanoHTTPD.Method.POST,
            postBody = """{"meshId": "googletv"}"""
        )
        val response = serverWithPeers.serve(session)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        assertTrue(SettingsStore.getKnownMeshes(mockContext).any { it.id == "googletv" })
    }

    // [PROGRAMMATIC] MESH-TEST-010: Deleting an empty, non-unmanaged mesh removes it and bumps config_version
    @Test
    fun testMeshDeleteRemovesEmptyMesh() {
        SettingsStore.addKnownMesh(mockContext, "googletv", "Google TV")
        val versionBefore = SettingsStore.getConfigVersion(mockContext)

        val session = mockSession(
            uri = "/api/mesh/delete",
            method = NanoHTTPD.Method.POST,
            postBody = """{"meshId": "googletv"}"""
        )
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertFalse(SettingsStore.getKnownMeshes(mockContext).any { it.id == "googletv" })
        assertTrue(SettingsStore.getConfigVersion(mockContext) > versionBefore)
    }

    // [PROGRAMMATIC] API-BEHAVE-008 (deprecated): "Update All" fanned an update trigger out to
    // every online peer fleet-wide with no mesh filter, contradicting its own confirm-dialog
    // text, and had no accepted use case. The endpoint must no longer exist.
    @Test
    fun testPeerUpdateAllEndpointRemoved() {
        val session = mockSession(
            uri = "/api/peers/update-all",
            method = NanoHTTPD.Method.POST,
            postBody = "{}"
        )
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
    }

    // [PROGRAMMATIC] API-TEST-010: self-update status is advisory — a release-fetch failure
    // is a 200 with updateAvailable=false and a checkError, never a 5xx.
    @Test
    fun testSelfUpdateStatusReturnsCheckErrorOnFetchFailure() {
        val session = mockSession("/api/self-update/status")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    // [PROGRAMMATIC] API-TEST-011: POST /api/self-update without auth returns 401
    @Test
    fun testSelfUpdateWithoutAuthReturns401() {
        SettingsStore.setPassword(mockContext, "secret123")
        val session = mockSession("/api/self-update", method = NanoHTTPD.Method.POST, postBody = "{}")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, response.status)
    }

    // [PROGRAMMATIC] API-TEST-012: POST /api/self-update invokes the dedicated self-update
    // coordinator, never the managed-app coordinator (statusFlow/mutex isolation).
    @Test
    fun testSelfUpdateAuthorizedInvokesDedicatedCoordinator() {
        val session = mockSession("/api/self-update", method = NanoHTTPD.Method.POST, postBody = "{}")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.ACCEPTED, response.status)
        verify(mockSelfUpdateCoordinator).startUpdateAsync(eq("com.cfox.droidmesh"), any(), eq(false), any())
        verify(mockCoordinator, org.mockito.kotlin.never()).startUpdateAsync(any(), any(), any(), any())
    }

    // [PROGRAMMATIC] API-TEST-016: force=true in the request body is actually threaded through
    // to the dedicated coordinator, not silently dropped
    @Test
    fun testSelfUpdateForceTrueIsPassedThrough() {
        val session = mockSession("/api/self-update", method = NanoHTTPD.Method.POST, postBody = "{\"force\": true}")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.ACCEPTED, response.status)
        verify(mockSelfUpdateCoordinator).startUpdateAsync(eq("com.cfox.droidmesh"), any(), eq(true), any())
    }

    // [PROGRAMMATIC] API-TEST-013: system settings launch endpoints require auth
    @Test
    fun testSystemSettingsEndpointsRequireAuth() {
        SettingsStore.setPassword(mockContext, "secret123")
        val a11ySession = mockSession("/api/system/open-accessibility-settings", method = NanoHTTPD.Method.POST, postBody = "{}")
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, server.serve(a11ySession).status)

        val installSession = mockSession("/api/system/open-install-settings", method = NanoHTTPD.Method.POST, postBody = "{}")
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, server.serve(installSession).status)
    }

    // [PROGRAMMATIC] API-TEST-014: system settings launch endpoints, authorized, return 200
    // and invoke Context.startActivity
    @Test
    fun testSystemSettingsEndpointsAuthorizedOpenSettings() {
        val a11ySession = mockSession("/api/system/open-accessibility-settings", method = NanoHTTPD.Method.POST, postBody = "{}")
        assertEquals(NanoHTTPD.Response.Status.OK, server.serve(a11ySession).status)

        val installSession = mockSession("/api/system/open-install-settings", method = NanoHTTPD.Method.POST, postBody = "{}")
        assertEquals(NanoHTTPD.Response.Status.OK, server.serve(installSession).status)

        verify(mockContext, atLeastOnce()).startActivity(any())
    }

    // [PROGRAMMATIC] PROV-TEST-004: GET /api/system/provisioning requires auth when a password
    // is configured, matching the other /api/system/* endpoints.
    @Test
    fun testProvisioningAuditRequiresAuthWhenPasswordSet() {
        SettingsStore.setPassword(mockContext, "secret123")
        val session = mockSession("/api/system/provisioning")
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, server.serve(session).status)
    }

    // [PROGRAMMATIC] PROV-TEST-004 (positive): authorized (no password configured), the audit
    // endpoint returns the expected 3-item shape.
    @Test
    fun testProvisioningAuditReturnsThreeItems() {
        val session = mockSession("/api/system/provisioning")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertTrue(json.has("repairNeeded"))
        assertEquals(3, json.getJSONArray("items").length())
    }

    // [PROGRAMMATIC] PROV-TEST-005: POST /api/system/provisioning/repair returns 409 and never
    // attempts a socket connection when ADB is disabled (Settings.Global.ADB_ENABLED defaults to
    // 0 under this project's unit-test stubbing, so AdbHelper.isAdbEnabled is always false here —
    // exercising exactly the fail-fast path PROV-BEHAVE-006 describes).
    @Test
    fun testProvisioningRepairFailsFastWhenAdbDisabled() {
        val session = mockSession("/api/system/provisioning/repair", method = NanoHTTPD.Method.POST, postBody = "{}")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.CONFLICT, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertEquals("error", json.getString("status"))
        assertTrue(json.getString("error").contains("ADB"))
    }

    // [PROGRAMMATIC] PROV-TEST-005 (auth): POST /api/system/provisioning/repair requires auth
    // when a password is configured, before it ever reaches the ADB-disabled check.
    @Test
    fun testProvisioningRepairRequiresAuthWhenPasswordSet() {
        SettingsStore.setPassword(mockContext, "secret123")
        val session = mockSession("/api/system/provisioning/repair", method = NanoHTTPD.Method.POST, postBody = "{}")
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, server.serve(session).status)
    }

    // [PROGRAMMATIC] API-TEST-031: the peer-relay outbound /update request carries a valid
    // Authorization bearer token when this device has a password configured, so a
    // password-protected target peer (including self-dispatch) doesn't 401 it silently.
    @Test
    fun testPeerUpdateRelayAttachesValidAuthTokenWhenPasswordSet() {
        SettingsStore.setPassword(mockContext, "secret123")
        val req = server.buildPeerUpdateRequest("192.168.1.5", 2325, "", "", "me.jxl.kiosk_satellite", true)
        val authHeader = req.header("Authorization")
        assertNotNull(authHeader)
        assertTrue(authHeader!!.startsWith("Bearer "))
        val token = authHeader.removePrefix("Bearer ")
        assertTrue(SettingsStore.validateToken(mockContext, token))
    }

    // [PROGRAMMATIC] API-TEST-032 (negative): no password configured means no token exists to
    // mint, and the target peer's own isAuthorized fails open too, so no header is attached.
    @Test
    fun testPeerUpdateRelayOmitsAuthHeaderWhenNoPasswordSet() {
        val req = server.buildPeerUpdateRequest("192.168.1.5", 2325, "", "", "me.jxl.kiosk_satellite", true)
        assertNull(req.header("Authorization"))
    }

    // [PROGRAMMATIC] API-TEST-015: /status includes canRequestPackageInstalls as a boolean
    @Test
    fun testStatusIncludesCanRequestPackageInstalls() {
        val session = mockSession("/status")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertTrue(json.has("canRequestPackageInstalls"))
        assertFalse(json.isNull("canRequestPackageInstalls"))
    }

    // [PROGRAMMATIC] PROV-TEST-006: index.html carries the Repair Needed banner (PROV-BEHAVE-007)
    // and its wiring — summary/items targets, the Repair Automatically button, and the JS
    // functions that populate and drive it.
    @Test
    fun testProvisioningRepairBannerAssetStructure() {
        val assetFile = java.io.File("src/main/assets/web/index.html")
        assertTrue("index.html asset must exist", assetFile.exists())
        val html = assetFile.readText()
        assertTrue("Must have provisioningRepairBanner", html.contains("id=\"provisioningRepairBanner\""))
        assertTrue("Must have provisioningRepairSummary", html.contains("id=\"provisioningRepairSummary\""))
        assertTrue("Must have provisioningRepairItems", html.contains("id=\"provisioningRepairItems\""))
        assertTrue("Must have provisioningRepairBtn", html.contains("id=\"provisioningRepairBtn\""))
        assertTrue("Must call refreshProvisioning on load", html.contains("refreshProvisioning()"))
        assertTrue("Must define repairProvisioningAutomatically", html.contains("async function repairProvisioningAutomatically()"))
    }

    // [PROGRAMMATIC] UI-TEST-005: index.html has single nav-item-device-settings and tab-device-settings
    @Test
    fun testDeviceSettingsAssetStructure() {
        val assetFile = java.io.File("src/main/assets/web/index.html")
        assertTrue("index.html asset must exist", assetFile.exists())
        val html = assetFile.readText()
        assertTrue(html.contains("id=\"nav-item-device-settings\""))
        assertTrue(html.contains("id=\"tab-device-settings\""))
        assertTrue(html.contains("id=\"settingWebServerPort\""))
        assertFalse(html.contains("id=\"nav-item-overview\""))
        assertFalse(html.contains("id=\"nav-item-settings\""))
        assertFalse(html.contains("id=\"tab-overview\""))
    }

    // [PROGRAMMATIC] UI-TEST-006: index.html has nav-item-global-settings and tab-global-settings
    // with fleet security, mesh partitions, persistent seeds, companion update, and config version
    @Test
    fun testGlobalSettingsAssetStructure() {
        val assetFile = java.io.File("src/main/assets/web/index.html")
        assertTrue("index.html asset must exist", assetFile.exists())
        val html = assetFile.readText()
        assertTrue("Must have nav-item-global-settings", html.contains("id=\"nav-item-global-settings\""))
        assertTrue("Must have tab-global-settings", html.contains("id=\"tab-global-settings\""))
        assertTrue("Must have globalPasswordStatusText", html.contains("id=\"globalPasswordStatusText\""))
        assertTrue("Must have globalMeshList", html.contains("id=\"globalMeshList\""))
        assertTrue("Must have globalCrossVlanSeedList", html.contains("id=\"globalCrossVlanSeedList\""))
        assertTrue("Must have globalDmVersion", html.contains("id=\"globalDmVersion\""))
        assertTrue("Must have globalConfigVersion", html.contains("id=\"globalConfigVersion\""))
    }

    // [PROGRAMMATIC] UI-TEST-007: the sidebar no longer carries inline "Add Mesh" / "Add Device"
    // actions — both live in Global Settings (UI-BEHAVE-008). Counting launcher call sites rather
    // than button labels: "Add Device" also appears as the modal's own title and submit label, so a
    // label-absence assertion would be unfalsifiable. Exactly one call site each = the Global
    // Settings card button, and none in the sidebar.
    @Test
    fun testSidebarHasNoInlineAddMeshOrAddDeviceActions() {
        val assetFile = java.io.File("src/main/assets/web/index.html")
        assertTrue("index.html asset must exist", assetFile.exists())
        val html = assetFile.readText()
        assertEquals(
            "showCreateMeshModal() must be launched from exactly one place (Global Settings)",
            1,
            Regex(Regex.escape("onclick=\"showCreateMeshModal()\"")).findAll(html).count()
        )
        assertEquals(
            "showConnectModal(true) must be launched from exactly one place (Global Settings)",
            1,
            Regex(Regex.escape("onclick=\"showConnectModal(true)\"")).findAll(html).count()
        )
        assertFalse(
            "Sidebar must not reference an \"Add Mesh\" button in help text",
            html.contains("\"Add Mesh\" button in the sidebar")
        )
    }

    // [PROGRAMMATIC] UI-TEST-008: the App Library renders as two separate cards split by ORIGIN
    // — Sideloaded Apps (full control set) and Store Apps (Managed + Auto Install only) — not by
    // managed-state, and not one mixed table.
    @Test
    fun testAppLibrarySplitIntoSideloadedAndStoreCards() {
        val assetFile = java.io.File("src/main/assets/web/index.html")
        assertTrue("index.html asset must exist", assetFile.exists())
        val html = assetFile.readText()
        for (id in listOf(
            "meshSideloadedLibraryBody",
            "meshSideloadedLibraryBadge",
            "meshSideloadedLibraryTableBody",
            "meshSideloadedLibraryChevron",
            "meshStoreLibraryBody",
            "meshStoreLibraryBadge",
            "meshStoreLibraryTableBody",
            "meshStoreLibraryChevron"
        )) {
            assertTrue("Must have id=\"$id\"", html.contains("id=\"$id\""))
        }
        assertFalse(
            "Old single-table App Library body must be gone",
            html.contains("id=\"meshLibraryTableBody\"")
        )
        assertFalse(
            "Prior managed-state-split card ids must be gone — the split is by origin now",
            html.contains("id=\"meshManagedLibraryTableBody\"") || html.contains("id=\"meshUnmanagedLibraryTableBody\"")
        )
        assertFalse(
            "Store-app rows must no longer render a dead \"N/A (Store App)\" target-version cell",
            html.contains("N/A (Store App)")
        )
    }

    // [PROGRAMMATIC] UI-TEST-010 (APP-BEHAVE-007 negative): the Store Apps card table must not
    // expose the Release Download URL, Target Version, or Auto Update columns — they never
    // apply to a Play Store app, and rendering them would recreate the dead-cell bug UI-BEHAVE-009
    // already fixed once for the old single table.
    @Test
    fun testStoreAppsCardHasNoUrlOrVersionColumns() {
        val assetFile = java.io.File("src/main/assets/web/index.html")
        assertTrue("index.html asset must exist", assetFile.exists())
        val html = assetFile.readText()
        val storeCardStart = html.indexOf("id=\"meshStoreLibraryBody\"")
        val storeCardEnd = html.indexOf("id=\"meshStoreLibraryTableBody\"")
        assertTrue("Store Apps card body must exist before its table body", storeCardStart in 0 until storeCardEnd)
        val theadStart = html.indexOf("<thead>", storeCardStart)
        val theadEnd = html.indexOf("</thead>", storeCardStart)
        assertTrue("Store Apps card must have a <thead> before its table body", theadStart in storeCardStart until storeCardEnd)
        assertTrue("Store Apps card's </thead> must close before its table body", theadEnd in theadStart until storeCardEnd)
        // Scoped to the column headers only — the card's own descriptive paragraph legitimately
        // says these column names don't apply, which would otherwise false-positive this check.
        val storeCardHeader = html.substring(theadStart, theadEnd)
        assertFalse("Store Apps card must not have a Target Version column", storeCardHeader.contains("Target Version"))
        assertFalse("Store Apps card must not have an Auto Update column", storeCardHeader.contains("Auto Update"))
        assertFalse("Store Apps card must not have a Release Download URL column", storeCardHeader.contains("Release Download URL"))
    }

    // [PROGRAMMATIC] UI-TEST-011 (UI-BEHAVE-012): the Sideloaded Apps card must not have a
    // standalone Managed checkbox/column any more — a non-blank Release Download URL already
    // implies Managed (SET-BEHAVE-005), so the URL field itself now patches `managed`, and the
    // dependent controls grey out on `hasReleaseSource(app)` rather than `app.managed`.
    @Test
    fun testSideloadedAppsCardHasNoManagedColumnAndCorrectOrder() {
        val assetFile = java.io.File("src/main/assets/web/index.html")
        assertTrue("index.html asset must exist", assetFile.exists())
        val html = assetFile.readText()

        val cardStart = html.indexOf("id=\"meshSideloadedLibraryBody\"")
        val tableBodyStart = html.indexOf("id=\"meshSideloadedLibraryTableBody\"")
        assertTrue("Sideloaded Apps card body must exist before its table body", cardStart in 0 until tableBodyStart)
        val theadStart = html.indexOf("<thead>", cardStart)
        val theadEnd = html.indexOf("</thead>", cardStart)
        assertTrue("Sideloaded Apps card must have a <thead> before its table body", theadStart in cardStart until tableBodyStart)
        val header = html.substring(theadStart, theadEnd)

        assertFalse("Sideloaded Apps card must no longer have a Managed column", header.contains("Managed"))
        val nameIdx = header.indexOf("App Name")
        val urlIdx = header.indexOf("Release Download URL")
        val versionIdx = header.indexOf("Target Version")
        val installIdx = header.indexOf("Auto Install")
        val updateIdx = header.indexOf("Auto Update")
        assertTrue("All five Sideloaded Apps columns must be present", listOf(nameIdx, urlIdx, versionIdx, installIdx, updateIdx).all { it >= 0 })
        assertTrue("Column order must be Name < URL < Target Version < Auto Install < Auto Update",
            nameIdx < urlIdx && urlIdx < versionIdx && versionIdx < installIdx && installIdx < updateIdx)

        val bodyFnStart = html.indexOf("function renderSideloadedLibraryRows")
        val bodyFnEnd = html.indexOf("\nfunction ", bodyFnStart + 1)
        assertTrue("renderSideloadedLibraryRows function must exist", bodyFnStart in 0 until bodyFnEnd)
        val fnBody = html.substring(bodyFnStart, bodyFnEnd)

        assertFalse(
            "Stale Managed-clearing hint text must be removed",
            html.contains("Clearing this un-checks Managed")
        )
        assertTrue(
            "The Release Download URL field must patch both downloadUrl and managed on change",
            fnBody.contains("{ downloadUrl: this.value, managed: !!this.value.trim() }")
        )
        assertFalse(
            "Sideloaded row rendering must no longer gate on app.managed for Auto Install/Target Version/Auto Update",
            fnBody.contains("!app.managed")
        )
        assertTrue(
            "Sideloaded row rendering must derive its gate from hasReleaseSource(app)",
            fnBody.contains("hasReleaseSource(app)")
        )
        assertTrue(
            "Sideloaded row rendering must gate Auto Install/Target Version/Auto Update on that derived value",
            Regex(Regex.escape("!isManaged")).findAll(fnBody).count() >= 3
        )
    }

    // [PROGRAMMATIC] UI-TEST-012 (UI-BEHAVE-013): the Sideloaded Apps card defaults to expanded,
    // Store Apps still defaults collapsed, and a peer's Installed Apps panel is seeded expanded
    // when its "N Installed" count is under 5 — but only when the user hasn't already toggled it.
    @Test
    fun testDefaultExpandStateForLibraryAndPeerCards() {
        val assetFile = java.io.File("src/main/assets/web/index.html")
        assertTrue("index.html asset must exist", assetFile.exists())
        val html = assetFile.readText()

        assertTrue(
            "meshSideloadedLibraryExpanded must default to true",
            html.contains("meshSideloadedLibraryExpanded: true")
        )
        assertTrue(
            "meshStoreLibraryExpanded must still default to false",
            html.contains("meshStoreLibraryExpanded: false")
        )

        val fnStart = html.indexOf("function renderMeshPeers")
        val fnEnd = html.indexOf("\nfunction ", fnStart + 1)
        assertTrue("renderMeshPeers function must exist", fnStart in 0 until fnEnd)
        val fnBody = html.substring(fnStart, fnEnd)
        assertTrue(
            "renderMeshPeers must seed peerAppsExpanded[peer.id] from the managed-apps count only when unset",
            fnBody.contains("state.peerAppsExpanded[peer.id] === undefined") &&
                fnBody.contains("state.peerAppsExpanded[peer.id] = managedApps.length < 5")
        )
    }

    // [PROGRAMMATIC] UI-TEST-013: index.html has #sidebarVersion and #badgeSelfUpdate in sidebar-foot,
    // formats status as Up-to-date (badge-sage) or Update (badge-rust), and no longer has #btnSelfUpdate.
    @Test
    fun testSidebarVersionBadgeStructure() {
        val assetFile = java.io.File("src/main/assets/web/index.html")
        assertTrue("index.html asset must exist", assetFile.exists())
        val html = assetFile.readText()

        assertTrue("Must have sidebarVersion element", html.contains("id=\"sidebarVersion\""))
        assertTrue("Must have badgeSelfUpdate badge element", html.contains("id=\"badgeSelfUpdate\""))
        assertFalse("Must not have old btnSelfUpdate button element", html.contains("id=\"btnSelfUpdate\""))

        val fnStart = html.indexOf("async function refreshSelfUpdateStatus")
        val fnEnd = html.indexOf("\nasync function ", fnStart + 1)
        assertTrue("refreshSelfUpdateStatus function must exist", fnStart in 0 until fnEnd)
        val fnBody = html.substring(fnStart, fnEnd)

        assertTrue("Must check badgeSelfUpdate element", fnBody.contains("document.getElementById('badgeSelfUpdate')"))
        assertTrue("Must format up-to-date state as Up-to-date with badge-sage", fnBody.contains("badge-sage") && fnBody.contains("'Up-to-date'"))
        assertTrue("Must format update available state as Update with badge-rust", fnBody.contains("badge-rust") && fnBody.contains("'Update'"))
    }

<<<<<<< HEAD
    // [PROGRAMMATIC] API-TEST-040: GET /status, /logs, /mesh, /api/settings, /check, /api/mesh/library,
    // and /api/mesh/persistent-connections all return 401 when password is set and unauthenticated.
    @Test
    fun testSensitiveEndpointsRequireAuthWhenPasswordSet() {
        SettingsStore.setPassword(mockContext, "secret123")

        val sensitiveEndpoints = listOf(
            "/status",
            "/api/status",
            "/api/health",
            "/logs",
            "/api/logs",
            "/mesh",
            "/peers",
            "/api/mesh",
            "/api/settings",
            "/check",
            "/api/check",
            "/api/releases",
            "/api/mesh/library",
            "/mesh/library",
            "/api/mesh/seeds",
            "/api/mesh/persistent-connections"
        )

        for (endpoint in sensitiveEndpoints) {
            val session = mockSession(endpoint)
            val response = server.serve(session)
            assertEquals("Endpoint $endpoint must return 401 when password is set and unauthenticated",
                NanoHTTPD.Response.Status.UNAUTHORIZED, response.status)
        }
    }

    // [PROGRAMMATIC] API-TEST-041: Public allowlist endpoints (HTML shell, auth status, login, logout,
    // and mesh gossip sync) succeed without auth even when password is set.
    @Test
    fun testPublicAllowlistEndpointsSucceedWithoutAuthWhenPasswordSet() {
        SettingsStore.setPassword(mockContext, "secret123")

        // 1. GET / (HTML shell)
        val rootSession = mockSession("/", headers = mapOf("accept" to "text/html"))
        val rootRes = server.serve(rootSession)
        assertEquals(NanoHTTPD.Response.Status.OK, rootRes.status)

        // 2. GET /index.html
        val indexSession = mockSession("/index.html")
        val indexRes = server.serve(indexSession)
        assertEquals(NanoHTTPD.Response.Status.OK, indexRes.status)

        // 3. GET /api/auth/status
        val authStatusSession = mockSession("/api/auth/status")
        val authStatusRes = server.serve(authStatusSession)
        assertEquals(NanoHTTPD.Response.Status.OK, authStatusRes.status)

        // 4. POST /api/login
        val loginSession = mockSession("/api/login", method = NanoHTTPD.Method.POST, postBody = """{"password":"secret123"}""")
        val loginRes = server.serve(loginSession)
        assertEquals(NanoHTTPD.Response.Status.OK, loginRes.status)

        // 5. POST /api/logout
        val logoutSession = mockSession("/api/logout", method = NanoHTTPD.Method.POST)
        val logoutRes = server.serve(logoutSession)
        assertEquals(NanoHTTPD.Response.Status.OK, logoutRes.status)

        // 6. GET /api/mesh/config (mesh pull)
        val meshConfigSession = mockSession("/api/mesh/config")
        val meshConfigRes = server.serve(meshConfigSession)
        assertEquals(NanoHTTPD.Response.Status.OK, meshConfigRes.status)

        // 7. POST /api/mesh/sync-config (mesh push)
        val syncConfigSession = mockSession("/api/mesh/sync-config", method = NanoHTTPD.Method.POST, postBody = """{"config_version":1}""")
        val syncConfigRes = server.serve(syncConfigSession)
        assertEquals(NanoHTTPD.Response.Status.OK, syncConfigRes.status)

        // 8. POST /api/mesh/handshake (mesh handshake)
        val handshakeSession = mockSession("/api/mesh/handshake", method = NanoHTTPD.Method.POST, postBody = """{"sender_ip":"192.168.1.100"}""")
        val handshakeRes = server.serve(handshakeSession)
        assertEquals(NanoHTTPD.Response.Status.OK, handshakeRes.status)
    }

    // [PROGRAMMATIC] API-TEST-042: GET / with Accept: application/json returns 401 when unauthenticated.
    @Test
    fun testRootWithJsonAcceptRequiresAuthWhenPasswordSet() {
        SettingsStore.setPassword(mockContext, "secret123")

        val unauthSession = mockSession("/", headers = mapOf("accept" to "application/json"))
        val unauthRes = server.serve(unauthSession)
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, unauthRes.status)

        val validToken = SettingsStore.generateToken(mockContext)
        val authedSession = mockSession("/", headers = mapOf(
            "accept" to "application/json",
            "authorization" to "Bearer $validToken"
        ))
        val authedRes = server.serve(authedSession)
        assertEquals(NanoHTTPD.Response.Status.OK, authedRes.status)
    }

    // [PROGRAMMATIC] API-TEST-043: Unknown/unrouted endpoints return 401 deny-by-default when password is set.
    @Test
    fun testUnknownEndpointReturns401WhenPasswordSet() {
        SettingsStore.setPassword(mockContext, "secret123")

        val unauthSession = mockSession("/nonexistent/endpoint")
        val unauthRes = server.serve(unauthSession)
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, unauthRes.status)

        val validToken = SettingsStore.generateToken(mockContext)
        val authedSession = mockSession("/nonexistent/endpoint", headers = mapOf(
            "authorization" to "Bearer $validToken"
        ))
        val authedRes = server.serve(authedSession)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, authedRes.status)
    }

    // [PROGRAMMATIC] API-TEST-044: /update with untrusted or cleartext URL is rejected with 400
    @Test
    fun testUpdateWithUntrustedUrlReturns400() {
        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.app",
                appName = "App",
                managed = true,
                downloadUrl = "https://github.com/owner/repo/releases"
            )
        )

        // 1. Cleartext HTTP
        val cleartextSession = mockSession(
            uri = "/api/update",
            method = NanoHTTPD.Method.POST,
            postBody = """{"package": "com.example.app", "tag": "v1.0.0", "url": "http://attacker.com/malicious.apk"}"""
        )
        val cleartextResponse = server.serve(cleartextSession)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, cleartextResponse.status)
        val cleartextJson = JSONObject(cleartextResponse.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertTrue(cleartextJson.getString("message").contains("untrusted") || cleartextJson.getString("message").contains("Insecure"))
        verify(mockCoordinator, never()).startUpdateForRelease(any(), any(), any(), any())

        // 2. Untrusted HTTPS domain
        val untrustedSession = mockSession(
            uri = "/api/update",
            method = NanoHTTPD.Method.POST,
            postBody = """{"package": "com.example.app", "tag": "v1.0.0", "url": "https://attacker.evil/malicious.apk"}"""
        )
        val untrustedResponse = server.serve(untrustedSession)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, untrustedResponse.status)
        verify(mockCoordinator, never()).startUpdateForRelease(any(), any(), any(), any())
    }

    // [PROGRAMMATIC] API-TEST-045: /update with valid HTTPS on trusted host is accepted
    @Test
    fun testUpdateWithTrustedHttpsUrlAccepted() {
        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.trusted",
                appName = "Trusted App",
                managed = true,
                downloadUrl = "https://github.com/owner/trusted/releases"
            )
        )

        val trustedSession = mockSession(
            uri = "/api/update",
            method = NanoHTTPD.Method.POST,
            postBody = """{"package": "com.example.trusted", "tag": "v1.0.0", "url": "https://github.com/owner/trusted/releases/download/v1.0.0/trusted.apk"}"""
        )
        val response = server.serve(trustedSession)
        assertEquals(NanoHTTPD.Response.Status.ACCEPTED, response.status)
        verify(mockCoordinator).startUpdateForRelease(
            eq("com.example.trusted"),
            any(),
            eq(false),
            any()
        )
    }
}

