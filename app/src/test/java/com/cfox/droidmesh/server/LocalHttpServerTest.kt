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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream

class LocalHttpServerTest {

    private val inMemoryPrefs = mutableMapOf<String, Any>()
    private lateinit var mockContext: Context
    private lateinit var mockCoordinator: UpdateCoordinator
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

        mockContext = mock {
            whenever(it.getSharedPreferences(any(), any())).thenAnswer { sharedPrefs }
            whenever(it.packageName).thenReturn("com.cfox.droidmesh")
            whenever(it.packageManager).thenReturn(mock())
            whenever(it.contentResolver).thenReturn(mock())
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
            onBlocking { it.checkVersion(any(), any()) } doReturn
                Result.failure(IllegalStateException("stubbed: no network in test"))
        }



        server = LocalHttpServer(
            context = mockContext,
            coordinator = mockCoordinator,
            meshManager = null,
            activePort = 2325
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

    // [PROGRAMMATIC] API-TEST-002: Mesh endpoint response
    @Test
    fun testMeshEndpointReturnsOk() {
        val session = mockSession("/api/mesh")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("application/json; charset=utf-8", response.mimeType)
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
        // 1. Initial GET config
        val getSession = mockSession("/api/mesh/config")
        val getRes = server.serve(getSession)
        assertEquals(NanoHTTPD.Response.Status.OK, getRes.status)

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

        // Verify that SettingsStore reflects the synced values
        assertEquals(2326, SettingsStore.getWebServerPort(mockContext))
        assertTrue(SettingsStore.isPasswordSet(mockContext))
        assertTrue(SettingsStore.getPersistentConnections(mockContext).contains("192.168.50.64:2326"))
        assertEquals(2000000000000L, SettingsStore.getConfigVersion(mockContext))
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

    // [PROGRAMMATIC] API-TEST-007 (API-BEHAVE-014/015): /status fails open with targetPackage=null
    // and an empty managedCandidates array when nothing is Managed.
    @Test
    fun testStatusFailsOpenWithNoManagedApp() {
        val session = mockSession("/api/status")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val json = JSONObject(response.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertTrue(json.isNull("targetPackage"))
        assertEquals(0, json.getJSONArray("managedCandidates").length())
    }

    // [PROGRAMMATIC] API-TEST-008: /check fails closed (400) with no managed app configured.
    @Test
    fun testCheckFailsClosedWithNoManagedApp() {
        val session = mockSession("/api/check")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
    }

    // [PROGRAMMATIC] API-TEST-009: /check auto-resolves the single managed app, then fails
    // closed (400) once a second managed app makes resolution ambiguous.
    @Test
    fun testCheckResolvesSingleManagedThenAmbiguous() {
        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.appone",
                appName = "App One",
                managed = true,
                downloadUrl = "https://example.com/appone.apk"
            )
        )
        val soloSession = mockSession("/api/check")
        val soloResponse = server.serve(soloSession)
        assertNotEquals(NanoHTTPD.Response.Status.BAD_REQUEST, soloResponse.status)

        SettingsStore.setMeshAppConfig(
            mockContext, "unmanaged",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.apptwo",
                appName = "App Two",
                managed = true,
                downloadUrl = "https://example.com/apptwo.apk"
            )
        )
        val ambiguousSession = mockSession("/api/check")
        val ambiguousResponse = server.serve(ambiguousSession)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, ambiguousResponse.status)
        val json = JSONObject(ambiguousResponse.data?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        assertEquals(2, json.getJSONArray("managedCandidates").length())

        // Explicit ?package= still resolves unambiguously.
        val explicitSession = mockSession("/api/check?package=com.example.appone")
        val explicitResponse = server.serve(explicitSession)
        assertNotEquals(NanoHTTPD.Response.Status.BAD_REQUEST, explicitResponse.status)
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
}
