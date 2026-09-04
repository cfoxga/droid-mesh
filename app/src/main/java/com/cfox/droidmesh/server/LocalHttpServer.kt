package com.cfox.droidmesh.server

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.cfox.droidmesh.BuildConfig
import com.cfox.droidmesh.api.ReleaseInfo
import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.mesh.MeshDiscoveryManager
import com.cfox.droidmesh.mesh.PeerNode
import com.cfox.droidmesh.service.AutoInstallService
import com.cfox.droidmesh.settings.SettingsStore
import com.cfox.droidmesh.utils.AdbHelper
import com.cfox.droidmesh.utils.CpuStatsHelper
import com.cfox.droidmesh.utils.Logger
import com.cfox.droidmesh.utils.ProvisioningAuditor
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class LocalHttpServer(
    private val context: Context,
    private val coordinator: UpdateCoordinator,
    private val meshManager: MeshDiscoveryManager? = null,
    val activePort: Int = 2325,
    // Dedicated coordinator for DroidMesh's own self-update — never the same instance as
    // `coordinator` in production, so a managed-app update never shares statusFlow/mutex with
    // a self-update check or trigger (see API-BEHAVE-016). Defaults to `coordinator` only for
    // call sites (tests, historical callers) that don't care about this isolation.
    private val selfUpdateCoordinator: UpdateCoordinator = coordinator
) : NanoHTTPD("0.0.0.0", activePort) {

    companion object {
        const val SELF_UPDATE_RELEASES_URL = "https://api.github.com/repos/cfoxga/droid-mesh/releases"

        // API-BEHAVE-035 (gitea#61): a forged Content-Length header must not be able to trigger
        // a multi-GB allocation attempt on a memory-constrained device before a single body byte
        // has actually arrived. 1MB comfortably covers every real JSON control-plane payload this
        // server accepts (config sync, mesh handshake, settings) with headroom to spare.
        const val MAX_REQUEST_BODY_BYTES = 1_048_576
    }

    // API-BEHAVE-037 (gitea#69): thrown by the requireInt/requireString field readers below so a
    // malformed field type produces a specific, safe, actionable 400 -- caught explicitly in
    // serve(), never falling through to the generic catch-all.
    private class InvalidFieldException(field: String, expected: String) :
        Exception("Invalid value for '$field': expected $expected")

    private fun requireInt(body: JSONObject, field: String): Int {
        return when (val raw = body.opt(field)) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: throw InvalidFieldException(field, "a number")
            else -> throw InvalidFieldException(field, "a number")
        }
    }

    private fun requireString(body: JSONObject, field: String): String {
        return body.opt(field) as? String ?: throw InvalidFieldException(field, "a string")
    }


    init {
        val pool = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "DroidMesh-HTTP-Worker").apply {
                isDaemon = false
            }
        }
        setAsyncRunner(object : AsyncRunner {
            override fun exec(code: ClientHandler) {
                pool.execute(code)
            }
            override fun closed(clientHandler: ClientHandler) {}
            override fun closeAll() {
                try { pool.shutdownNow() } catch (_: Exception) {}
            }
        })
    }

    override fun createClientHandler(accept: java.net.Socket, inputStream: InputStream): ClientHandler {
        val rawAddress = accept.inetAddress
        val fastAddress = try {
            val ip = rawAddress.hostAddress ?: "127.0.0.1"
            java.net.InetAddress.getByAddress(ip, rawAddress.address)
        } catch (_: Exception) {
            rawAddress
        }
        val wrapper = object : java.net.Socket() {
            override fun getInetAddress(): java.net.InetAddress = fastAddress
            override fun getInputStream(): InputStream = accept.getInputStream()
            override fun getOutputStream(): java.io.OutputStream = accept.getOutputStream()
            override fun close() = accept.close()
            override fun isClosed(): Boolean = accept.isClosed
            override fun isConnected(): Boolean = accept.isConnected
            override fun setSoTimeout(timeout: Int) { accept.soTimeout = timeout }
            override fun getSoTimeout(): Int = accept.soTimeout
        }
        return super.createClientHandler(wrapper, inputStream)
    }





    private val httpClient = OkHttpClient.Builder()

        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)

    private val webHtmlBytes by lazy {
        try {
            context.assets.open("web/index.html").use { it.readBytes() }
        } catch (e: Exception) {
            Logger.e("Failed to load web/index.html from assets", e)
            ByteArray(0)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Logger.i("LocalHttpServer.serve: $method $uri from ${session.remoteIpAddress}")

        // API-BEHAVE-035 (gitea#61): reject an oversized Content-Length before ever allocating a
        // buffer for it -- the attacker never has to actually send that much data for the
        // allocation itself to OOM-crash the foreground service.
        val declaredLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (declaredLength > MAX_REQUEST_BODY_BYTES) {
            return jsonResponse(
                Response.Status.PAYLOAD_TOO_LARGE,
                JSONObject().apply {
                    put("status", "error")
                    put("error", "Request body exceeds maximum allowed size ($MAX_REQUEST_BODY_BYTES bytes)")
                }
            )
        }

        // Deny-by-default endpoint authorization (API-BEHAVE-028 / gitea#36). API-BEHAVE-034
        // (gitea#55): isAuthorized() unconditionally passes every request while no admin password
        // is configured yet, since there is no token to present until one is set -- that must not
        // mean every state-mutating endpoint is wide open until an admin happens to visit the
        // settings screen. A missing password fails a state-changing request closed instead;
        // read-only/status endpoints stay reachable pre-bootstrap exactly as before, so the
        // in-app "set a password" flow (served from this same port) never gets locked out of
        // itself before it exists.
        if (!isPublicEndpoint(session)) {
            val passwordSet = SettingsStore.isPasswordSet(context)
            val denied = if (!passwordSet) {
                isStateChangingEndpoint(session)
            } else {
                !isAuthorized(session)
            }
            if (denied) {
                val message = if (!passwordSet) {
                    "An admin password must be set before this action is allowed. POST /api/password to set one."
                } else {
                    "Unauthorized"
                }
                return jsonResponse(
                    Response.Status.UNAUTHORIZED,
                    JSONObject().apply {
                        put("status", "error")
                        put("error", message)
                    }
                )
            }
        }

        return try {
            when {
                // Static Web Administration Interface — only "/" and "/index.html" serve the
                // SPA shell. "/overview"/"/settings" no longer exist as tabs (UI-BEHAVE-007
                // consolidated them into Device Settings); "/mesh" and "/logs" are real JSON
                // API paths below and must not be shadowed by this branch (a bare `when` takes
                // the first match, so listing them here permanently hid handleMesh()/handleLogs()
                // from ever running for the bare path — found via live-fleet /logs verification).
                (uri == "/" || uri == "/index.html") && method == Method.GET -> {
                    val accept = session.headers["accept"] ?: ""
                    if (accept.contains("application/json") && uri == "/") {
                        handleStatus(session)
                    } else {
                        handleServeWeb()
                    }
                }

                // Auth Endpoints
                uri == "/api/auth/status" && method == Method.GET -> handleAuthStatus(session)
                uri == "/api/login" && method == Method.POST -> handleLogin(session)
                uri == "/api/logout" && method == Method.POST -> handleLogout()
                uri == "/api/password" && method == Method.POST -> handlePassword(session)

                // Status & Health
                (uri == "/status" || uri == "/api/status" || uri == "/api/health") && method == Method.GET -> handleStatus(session)

                // Settings
                uri == "/api/settings" && method == Method.GET -> handleGetSettings()
                uri == "/api/settings" && method == Method.POST -> handlePostSettings(session)

                // Releases & Updates
                (uri == "/check" || uri == "/api/check" || uri == "/api/releases") && method == Method.GET -> handleCheck(session)
                (uri == "/update" || uri == "/api/update") && (method == Method.POST || method == Method.GET) -> handleUpdate(session)

                // Network ADB
                (uri == "/adb/toggle" || uri == "/api/adb/toggle") && (method == Method.POST || method == Method.GET) -> handleAdbToggle(session)

                // Self-Update (DroidMesh updating its own APK)
                uri == "/api/self-update/status" && method == Method.GET -> handleSelfUpdateStatus(session)
                uri == "/api/self-update" && method == Method.POST -> handleSelfUpdate(session)

                // System Settings Launch
                uri == "/api/system/open-accessibility-settings" && method == Method.POST -> handleOpenAccessibilitySettings(session)
                uri == "/api/system/open-install-settings" && method == Method.POST -> handleOpenInstallSettings(session)
                uri == "/api/system/provisioning" && method == Method.GET -> handleProvisioningAudit(session)
                uri == "/api/system/provisioning/repair" && method == Method.POST -> handleProvisioningRepair(session)

                // Peer Mesh Fleet
                (uri == "/mesh" || uri == "/peers" || uri == "/api/mesh") && method == Method.GET -> handleMesh()
                (uri == "/api/mesh/library" || uri == "/mesh/library") && method == Method.GET -> handleMeshLibraryGet(session)
                (uri == "/api/mesh/library" || uri == "/mesh/library") && method == Method.POST -> handleMeshLibraryPost(session)
                uri == "/api/mesh/config" && method == Method.GET -> handleMeshConfigGet()
                uri == "/api/mesh/sync-config" && method == Method.POST -> handleMeshConfigSync(session)
                uri == "/api/mesh/beacon" && (method == Method.POST || method == Method.GET) -> handleMeshBeacon(session)
                uri == "/api/mesh/connect" && method == Method.POST -> handleMeshConnect(session)
                uri == "/api/mesh/handshake" && method == Method.POST -> handleMeshHandshake(session)
                (uri == "/api/mesh/seeds" || uri == "/api/mesh/persistent-connections") && method == Method.GET -> handleMeshPersistentConnectionsGet()
                (uri == "/api/mesh/seeds" || uri == "/api/mesh/seeds/remove" || uri == "/api/mesh/persistent-connections" || uri == "/api/mesh/persistent-connections/remove") && (method == Method.DELETE || method == Method.POST) -> handleMeshPersistentConnectionsModify(session)
                uri == "/api/mesh/create" && method == Method.POST -> handleMeshCreate(session)
                uri == "/api/mesh/update" && method == Method.POST -> handleMeshUpdate(session)
                uri == "/api/mesh/delete" && method == Method.POST -> handleMeshDelete(session)
                uri == "/api/peers/update" && method == Method.POST -> handlePeerUpdate(session)
                uri == "/api/peers/adb/toggle" && method == Method.POST -> handlePeerAdbToggle(session)

                // Runtime Logs
                (uri == "/logs" || uri == "/api/logs") && method == Method.GET -> handleLogs()
                uri == "/api/logs/clear" && method == Method.POST -> handleLogsClear(session)

                else -> {
                    jsonResponse(
                        Response.Status.NOT_FOUND,
                        JSONObject().apply {
                            put("status", "error")
                            put("message", "Endpoint not found: ${method.name} $uri")
                        }
                    )
                }
            }
        } catch (e: InvalidFieldException) {
            // API-BEHAVE-037 (gitea#69): a malformed field type gets a specific, safe 400 naming
            // the field instead of falling through to the generic catch-all below.
            jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().apply {
                    put("status", "error")
                    put("error", e.message)
                }
            )
        } catch (e: Exception) {
            // API-BEHAVE-037 (gitea#69): the real exception is still logged server-side (visible
            // via /logs to an authenticated admin), but the raw message never reaches the client
            // response body verbatim -- an uncaught exception's text is not something a remote
            // caller is entitled to see, whatever it happens to contain.
            Logger.e("Error processing HTTP request ($method $uri)", e)
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().apply {
                    put("status", "error")
                    put("error", "Internal Server Error")
                }
            )
        }
    }

    // --- Authentication Helpers ---

    // API-BEHAVE-032 (gitea#41 L3): the URL query-param fallback (`?token=`) is deliberately
    // NOT supported here. Tokens in URLs risk exposure via server access logs, browser history,
    // and Referer headers -- only header and cookie delivery are accepted.
    private fun extractToken(session: IHTTPSession): String? {
        val authHeader = session.headers["authorization"] ?: session.headers["Authorization"]
        if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ", ignoreCase = true)) {
            return authHeader.substring(7).trim()
        }

        val customHeader = session.headers["x-auth-token"] ?: session.headers["X-Auth-Token"]
        if (!customHeader.isNullOrBlank()) {
            return customHeader.trim()
        }

        val cookieHeader = session.headers["cookie"] ?: session.headers["Cookie"]
        if (!cookieHeader.isNullOrBlank()) {
            val token = cookieHeader.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("auth_token=") }
                ?.substringAfter("auth_token=")
            if (!token.isNullOrBlank()) return token
        }

        return null
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (!SettingsStore.isPasswordSet(context)) return true
        val token = extractToken(session)
        return SettingsStore.validateToken(context, token)
    }

    private fun isPublicEndpoint(session: IHTTPSession): Boolean {
        val uri = session.uri
        val method = session.method

        // Static Web Administration SPA HTML shell (GET / and GET /index.html).
        // If Accept contains "application/json" on "/", it queries status JSON (API-BEHAVE-001/028)
        // and requires authentication when a password is configured.
        if ((uri == "/" || uri == "/index.html") && method == Method.GET) {
            val accept = session.headers["accept"] ?: session.headers["Accept"] ?: ""
            if (!accept.contains("application/json") || uri != "/") {
                return true
            }
        }

        // Public Auth endpoints
        if (uri == "/api/auth/status" && method == Method.GET) return true
        if (uri == "/api/login" && method == Method.POST) return true
        if (uri == "/api/logout" && method == Method.POST) return true

        // Mesh Gossip Protocol endpoints (API-BEHAVE-027)
        if (uri == "/api/mesh/config" && method == Method.GET) return true
        if (uri == "/api/mesh/sync-config" && method == Method.POST) return true
        if (uri == "/api/mesh/handshake" && method == Method.POST) return true

        return false
    }

    // API-BEHAVE-034 (gitea#55): every non-public endpoint that mutates device state (installs
    // apps, toggles ADB, changes mesh membership, edits settings, launches system UI, clears
    // logs) must fail closed while no admin password exists, since there is no way to present a
    // valid token before one does. Deliberately excludes the mesh gossip endpoints
    // (/api/mesh/sync-config, /api/mesh/handshake, /api/mesh/beacon) -- those are a separate,
    // already-public-by-design trust boundary tracked under API-OPEN-003/gitea#52, not this gap.
    private fun isStateChangingEndpoint(session: IHTTPSession): Boolean {
        val uri = session.uri
        val method = session.method
        return when {
            uri == "/update" || uri == "/api/update" -> true
            uri == "/adb/toggle" || uri == "/api/adb/toggle" -> true
            uri == "/api/self-update" && method == Method.POST -> true
            uri == "/api/system/provisioning/repair" && method == Method.POST -> true
            uri == "/api/settings" && method == Method.POST -> true
            (uri == "/api/mesh/library" || uri == "/mesh/library") && method == Method.POST -> true
            uri == "/api/mesh/create" || uri == "/api/mesh/update" || uri == "/api/mesh/delete" -> true
            uri == "/api/mesh/connect" && method == Method.POST -> true
            (uri == "/api/mesh/seeds" || uri == "/api/mesh/seeds/remove" ||
                uri == "/api/mesh/persistent-connections" || uri == "/api/mesh/persistent-connections/remove") &&
                (method == Method.DELETE || method == Method.POST) -> true
            uri == "/api/peers/update" && method == Method.POST -> true
            uri == "/api/peers/adb/toggle" && method == Method.POST -> true
            uri == "/api/logs/clear" && method == Method.POST -> true
            uri == "/api/system/open-accessibility-settings" && method == Method.POST -> true
            uri == "/api/system/open-install-settings" && method == Method.POST -> true
            else -> false
        }
    }

    // Best-effort bind probe — briefly opens and immediately closes a socket on the candidate
    // port to check nothing else on-device already holds it. Racy in theory (TOCTOU against a
    // process binding between probe and actual rebind), but that race exists on the read side of
    // any bind check; it converts the common case (a stale/wrong port) from a silent brick into
    // an explicit 400 up front.
    private fun isPortAvailable(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun parseJsonBody(session: IHTTPSession): JSONObject {
        if (session.method != Method.POST && session.method != Method.PUT && session.method != Method.PATCH && session.method != Method.DELETE) {
            return JSONObject()
        }
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return JSONObject()

        return try {
            val buf = ByteArray(contentLength)
            var totalRead = 0
            val input = session.inputStream
            while (totalRead < contentLength) {
                val count = input.read(buf, totalRead, contentLength - totalRead)
                if (count <= 0) break
                totalRead += count
            }
            val text = String(buf, 0, totalRead, Charsets.UTF_8)
            if (text.isNotBlank()) JSONObject(text) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }


    // --- Web Assets Serving ---

    private fun handleServeWeb(): Response {
        val bytes = webHtmlBytes
        if (bytes.isEmpty()) {
            val res = newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Web administration UI asset not found."
            )
            addStandardHeaders(res)
            return res
        }
        val html = String(bytes, Charsets.UTF_8)
        val res = newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            html
        )
        addStandardHeaders(res)
        return res
    }



    // --- Endpoint Handlers ---

    private fun handleAuthStatus(session: IHTTPSession): Response {
        val pwdSet = SettingsStore.isPasswordSet(context)
        val authed = if (pwdSet) isAuthorized(session) else true

        val json = JSONObject().apply {
            put("status", "ok")
            put("passwordSet", pwdSet)
            put("authenticated", authed)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleLogin(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val password = body.optString("password", session.parms["password"] ?: "")

        if (SettingsStore.verifyPassword(context, password)) {
            val token = SettingsStore.generateToken(context)
            val json = JSONObject().apply {
                put("status", "ok")
                put("message", "Authentication successful")
                put("token", token)
                put("expiresIn", 7 * 86400)
            }
            return jsonResponse(Response.Status.OK, json, cookies = mapOf("auth_token" to token))
        } else {
            val json = JSONObject().apply {
                put("status", "error")
                put("error", "Invalid admin password")
            }
            return jsonResponse(Response.Status.UNAUTHORIZED, json)
        }
    }

    private fun handleLogout(): Response {
        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Logged out")
        }
        val res = jsonResponse(Response.Status.OK, json)
        res.addHeader("Set-Cookie", "auth_token=; Path=/; Max-Age=0; SameSite=Lax")
        return res
    }

    private fun handlePassword(session: IHTTPSession): Response {
        if (SettingsStore.isPasswordSet(context) && !isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Authentication required to change password")
            })
        }

        val body = parseJsonBody(session)
        val currentPassword = body.optString("currentPassword", "")
        val newPassword = body.optString("password", "")

        if (SettingsStore.isPasswordSet(context)) {
            if (!SettingsStore.verifyPassword(context, currentPassword)) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                    put("status", "error")
                    put("error", "Current password does not match")
                })
            }
        }

        SettingsStore.setPassword(context, newPassword)
        meshManager?.syncConfigToMesh()
        val newToken = if (newPassword.isNotBlank()) SettingsStore.generateToken(context) else null

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", if (newPassword.isBlank()) "Admin password removed" else "Admin password updated successfully")
            if (newToken != null) {
                put("token", newToken)
            }
        }

        val cookies = if (newToken != null) mapOf("auth_token" to newToken) else mapOf("auth_token" to "")
        return jsonResponse(Response.Status.OK, json, cookies = cookies)
    }

    // Looks up a single App Library entry by explicit package name. There is no implicit
    // "the managed app" resolution any more, no ambiguity, and no hardcoded default target app
    // or release URL anywhere in this class — downloadUrl on the resolved entry is the only
    // source of release information. See API-BEHAVE-018 (API-BEHAVE-014/015 deprecated).
    private fun findAppLibraryEntry(packageName: String): SettingsStore.MeshAppConfig? {
        val meshId = SettingsStore.getLocalMeshId(context)
        val library = SettingsStore.getMeshAppLibrary(context, meshId)
        return library[packageName]
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val installedApps = AppVersionHelper.getUserInstalledApps(context)
        val currentStatus = coordinator.statusFlow.value
        val telemetry = CpuStatsHelper.readTelemetry()

        val json = JSONObject().apply {
            put("status", "ok")
            put("app", "DroidMesh")
            put("version", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)

            val appsArray = JSONArray()
            for (app in installedApps) {
                appsArray.put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("appName", app.appName)
                    put("versionName", app.versionName ?: JSONObject.NULL)
                    put("versionCode", app.versionCode ?: JSONObject.NULL)
                })
            }
            put("installedApps", appsArray)

            put("accessibilityServiceActive", AutoInstallService.isServiceRunning)
            put("webServerPort", SettingsStore.getWebServerPort(context))
            put("adbEnabled", AdbHelper.isAdbEnabled(context))
            put("canRequestPackageInstalls", context.packageManager.canRequestPackageInstalls())
            put("updaterState", currentStatus.state)
            put("updaterMessage", currentStatus.message)
            put("progressPercent", currentStatus.progressPercent)
            put("deviceModel", CpuStatsHelper.getDeviceName(context))
            put("cpuUsage", telemetry.usagePercent ?: JSONObject.NULL)
            put("cpuTemp", telemetry.tempCelsius ?: JSONObject.NULL)
            put("passwordConfigured", SettingsStore.isPasswordSet(context))
            put("configVersion", SettingsStore.getConfigVersion(context))
        }

        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleGetSettings(): Response {
        val seedsJson = JSONArray()
        SettingsStore.getPersistentConnections(context).forEach { seedsJson.put(it) }

        val json = JSONObject().apply {
            put("status", "ok")
            put("webServerPort", SettingsStore.getWebServerPort(context))
            put("hasPassword", SettingsStore.isPasswordSet(context))
            put("adbEnabled", AdbHelper.isAdbEnabled(context))
            put("localMeshId", SettingsStore.getLocalMeshId(context))
            put("localMeshName", SettingsStore.getLocalMeshName(context))
            put("customDeviceName", SettingsStore.getCustomDeviceName(context))
            put("effectiveDeviceName", CpuStatsHelper.getDeviceName(context))
            put("crossVlanSeeds", seedsJson)
            put("configVersion", SettingsStore.getConfigVersion(context))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handlePostSettings(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        var settingsChanged = false
        if (body.has("webServerPort")) {
            val port = requireInt(body, "webServerPort")
            // The WebView shell's entire UI is served by this port now (UI-BEHAVE-005) — accepting
            // an out-of-range or already-bound port here would strand the app with no in-app way to
            // recover, the same failure mode the webServerEnabled removal was meant to close off.
            // Client-side JS validation is not a substitute: this endpoint is reachable directly.
            if (port !in 1024..65535) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                    put("status", "error")
                    put("error", "webServerPort must be between 1024 and 65535")
                })
            }
            if (port != activePort && !isPortAvailable(port)) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                    put("status", "error")
                    put("error", "Port $port is already in use on this device; refusing to apply a change that would make the local UI unreachable")
                })
            }
            SettingsStore.setWebServerPort(context, port)
            Logger.i("Web server port updated via HTTP API: $port")
            settingsChanged = true
        }
        if (body.has("localMeshId")) {
            val meshId = requireString(body, "localMeshId").trim()
            if (meshId.isNotBlank()) {
                SettingsStore.setLocalMeshId(context, meshId)
                Logger.i("Local mesh ID updated: $meshId")
                settingsChanged = true
            }
        }
        if (body.has("localMeshName")) {
            val meshName = requireString(body, "localMeshName").trim()
            if (meshName.isNotBlank()) {
                SettingsStore.setLocalMeshName(context, meshName)
                Logger.i("Local mesh name updated: $meshName")
                settingsChanged = true
            }
        }
        if (body.has("customDeviceName")) {
            val deviceName = requireString(body, "customDeviceName").trim()
            SettingsStore.setCustomDeviceName(context, deviceName)
            if (deviceName.isNotBlank()) {
                Logger.i("Custom device name set: $deviceName")
            } else {
                Logger.i("Custom device name cleared, will use default system name")
            }
            settingsChanged = true
        }

        if (settingsChanged) {
            meshManager?.syncConfigToMesh()
        }

        return handleGetSettings()
    }

    private fun handleCheck(session: IHTTPSession): Response {
        val force = session.parms["force"]?.toBoolean() ?: false
        val packageName = session.parms["package"]?.trim()?.ifBlank { null }
            ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "package is required")
            })

        val entry = findAppLibraryEntry(packageName)
            ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "$packageName not found in App Library")
            })
        val downloadUrl = entry.downloadUrl.trim()
        if (downloadUrl.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "No downloadUrl configured for $packageName")
            })
        }

        val releasesResult = runBlocking { coordinator.fetchAvailableReleases(downloadUrl, forceRefresh = force) }
        // Reuses whatever the fetch above just cached (UPD-BEHAVE-011) instead of firing a second
        // upstream request per /check, which is what doubled this endpoint's rate-limit cost.
        val checkResult = runBlocking { coordinator.checkVersion(packageName, downloadUrl) }

        fun releasesJson(list: List<com.cfox.droidmesh.api.ReleaseInfo>) = JSONArray().apply {
            for (rel in list) {
                put(JSONObject().apply {
                    put("name", rel.name)
                    put("tagName", rel.tagName)
                    put("publishedAt", rel.publishedAt)
                    put("apkAssetUrl", rel.apkAssetUrl)
                    put("apkFileName", rel.apkFileName)
                    put("apkSize", rel.apkSize)
                })
            }
        }

        if (checkResult.isSuccess) {
            val comp = checkResult.getOrThrow()
            val json = JSONObject().apply {
                put("status", "ok")
                put("targetPackage", packageName)
                put("installedVersionName", comp.installedVersionName ?: JSONObject.NULL)
                put("installedVersionCode", comp.installedVersionCode ?: JSONObject.NULL)
                put("latestVersionTag", comp.latestVersionTag)
                put("updateAvailable", comp.isUpdateAvailable)
                put("release", JSONObject().apply {
                    put("name", comp.releaseInfo.name)
                    put("tagName", comp.releaseInfo.tagName)
                    put("publishedAt", comp.releaseInfo.publishedAt)
                    put("apkAssetUrl", comp.releaseInfo.apkAssetUrl)
                    put("apkFileName", comp.releaseInfo.apkFileName)
                    put("apkSize", comp.releaseInfo.apkSize)
                })

                put("releases", releasesJson(releasesResult.getOrElse { emptyList() }))
            }
            return jsonResponse(Response.Status.OK, json)
        } else {
            // The version comparison failed, but the release list may still have resolved (or be
            // cached from an earlier fetch). Return it rather than a bare 500 with no releases —
            // otherwise the Web UI's Target Version selector collapses to just "latest"
            // (UPD-BEHAVE-010).
            val err = checkResult.exceptionOrNull()?.message ?: "Check failed"
            val fallback = releasesResult.getOrElse { runBlocking { coordinator.getCachedReleases(downloadUrl) } }
            val json = JSONObject().apply {
                put("status", if (fallback.isEmpty()) "error" else "partial")
                put("message", err)
                put("targetPackage", packageName)
                put("releases", releasesJson(fallback))
            }
            return jsonResponse(
                if (fallback.isEmpty()) Response.Status.INTERNAL_ERROR else Response.Status.OK,
                json
            )
        }
    }

    private fun handleUpdate(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val force = body.optBoolean("force", session.parms["force"]?.toBoolean() ?: false)
        val tag = body.optString("tag", session.parms["tag"] ?: session.parms["version"] ?: "")
        val url = body.optString("url", session.parms["url"] ?: session.parms["download_url"] ?: "")
        val filenameOverride = body.optString("filename", session.parms["filename"] ?: "")
        // [API-BEHAVE-033] Reject an unsafe filename/tag override at the API boundary with a clear
        // 400 before it ever reaches ApkDownloader (which independently enforces the same
        // whitelist as its own choke point -- UPD-BEHAVE-015 -- for every caller, not only this
        // endpoint). gitea#53.
        if (filenameOverride.isNotBlank() && !com.cfox.droidmesh.downloader.ApkDownloader.isSafeApkFileName(filenameOverride)) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "Invalid or unsafe filename: $filenameOverride")
            })
        }
        val packageName = body.optString("package", session.parms["package"] ?: "").trim().ifBlank { null }
            ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "package is required")
            })

        val entry = findAppLibraryEntry(packageName)
            ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "$packageName not found in App Library")
            })

        if (url.isNotBlank()) {
            val configuredHost = entry.downloadUrl.takeIf { it.isNotBlank() }?.let {
                try { java.net.URI(it).host } catch (_: Exception) { null }
            }
            if (!com.cfox.droidmesh.security.TrustedReleaseHosts.isTrustedReleaseUrl(url, configuredHost)) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                    put("status", "error")
                    put("message", "Insecure or untrusted download URL: $url")
                })
            }
        }

        if (url.isNotBlank() && tag.isNotBlank()) {
            val specificRelease = ReleaseInfo(
                tagName = tag,
                name = tag,
                publishedAt = "",
                apkAssetUrl = url,
                apkFileName = filenameOverride.ifBlank { "$packageName-$tag.apk" },
                apkSize = 0L
            )
            coordinator.startUpdateForRelease(packageName, specificRelease, force = force)
        } else {
            val downloadUrl = entry.downloadUrl.trim()
            if (downloadUrl.isBlank()) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                    put("status", "error")
                    put("message", "No downloadUrl configured for $packageName")
                })
            }
            if (tag.isNotBlank()) {
                // API-BEHAVE-021: a `tag` with no explicit `url` is still a pin. Resolve it against
                // the entry's release source and install THAT build. Falling through to
                // startUpdateAsync here would silently install the newest release instead.
                val resolved = runBlocking { coordinator.resolveTargetRelease(downloadUrl, tag) }
                val release = resolved.getOrElse { err ->
                    return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                        put("status", "error")
                        put(
                            "message",
                            "Could not resolve version '$tag' for $packageName: " +
                                (err.message ?: err.javaClass.simpleName)
                        )
                    })
                }
                coordinator.startUpdateForRelease(packageName, release, force = force)
            } else {
                coordinator.startUpdateAsync(packageName, downloadUrl, force = force)
            }
        }

        val json = JSONObject().apply {
            put("status", "accepted")
            put("message", "Update sequence initiated (force=$force, target=${tag.ifBlank { "latest" }})")
            put("targetPackage", packageName)
            put("accessibilityServiceActive", AutoInstallService.isServiceRunning)
        }

        return jsonResponse(Response.Status.ACCEPTED, json)
    }

    private fun handleAdbToggle(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        AdbHelper.toggleAdb(context)
        val json = JSONObject().apply {
            put("status", "ok")
            put("adbEnabled", AdbHelper.isAdbEnabled(context))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    // --- Self-Update (DroidMesh updating its own APK) ---
    // Advisory endpoint — never fails closed on a release-fetch error, since polling status
    // should never surface a 5xx just because GitHub is briefly unreachable (API-BEHAVE-016).
    private fun handleSelfUpdateStatus(session: IHTTPSession): Response {
        val checkResult = runBlocking {
            selfUpdateCoordinator.checkVersion(context.packageName, SELF_UPDATE_RELEASES_URL)
        }
        val currentStatus = selfUpdateCoordinator.statusFlow.value

        val json = JSONObject().apply {
            put("status", "ok")
            put("currentVersion", BuildConfig.VERSION_NAME)
            put("updaterState", currentStatus.state)
            put("updaterMessage", currentStatus.message)
            put("progressPercent", currentStatus.progressPercent)
            if (checkResult.isSuccess) {
                val comp = checkResult.getOrThrow()
                put("latestVersionTag", comp.latestVersionTag)
                put("updateAvailable", comp.isUpdateAvailable)
            } else {
                put("latestVersionTag", JSONObject.NULL)
                put("updateAvailable", false)
                put("checkError", checkResult.exceptionOrNull()?.message ?: "Self-update check failed")
            }
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleSelfUpdate(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val force = body.optBoolean("force", session.parms["force"]?.toBoolean() ?: false)
        selfUpdateCoordinator.startUpdateAsync(context.packageName, SELF_UPDATE_RELEASES_URL, force = force)

        val json = JSONObject().apply {
            put("status", "accepted")
            put("message", "Self-update sequence initiated (force=$force)")
        }
        return jsonResponse(Response.Status.ACCEPTED, json)
    }

    // --- System Settings Launch ---
    // Both use FLAG_ACTIVITY_NEW_TASK because they're invoked from the foreground service's
    // Context, not an Activity — same pattern AdbHelper.toggleAdb already uses.

    private fun handleOpenAccessibilitySettings(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }
        return try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            jsonResponse(Response.Status.OK, JSONObject().apply { put("status", "ok") })
        } catch (e: Exception) {
            Logger.e("Cannot open accessibility settings", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Failed to open accessibility settings")
            })
        }
    }

    private fun handleOpenInstallSettings(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }
        return try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            jsonResponse(Response.Status.OK, JSONObject().apply { put("status", "ok") })
        } catch (e: Exception) {
            Logger.e("Cannot open unknown app sources settings", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Failed to open install settings")
            })
        }
    }

    // --- Self-Provisioning Audit & Repair (PROV-API-001/002) ---

    private fun provisioningAuditJson(audit: ProvisioningAuditor.ProvisioningAuditResult): JSONObject {
        val json = JSONObject()
        json.put("status", "ok")
        json.put("repairNeeded", audit.repairNeeded)
        val items = JSONArray()
        audit.items.forEach { item ->
            items.put(JSONObject().apply {
                put("key", item.key)
                put("label", item.label)
                put("satisfied", item.satisfied)
                put("externalCommand", item.externalCommand)
            })
        }
        json.put("items", items)
        return json
    }

    private fun handleProvisioningAudit(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }
        val audit = ProvisioningAuditor.audit(context)
        return jsonResponse(Response.Status.OK, provisioningAuditJson(audit))
    }

    private fun handleProvisioningRepair(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }
        val result = runBlocking { ProvisioningAuditor.repair(context) }
        if (result.isFailure) {
            // PROV-BEHAVE-006: only failure path today is "ADB is not enabled" (fails fast,
            // before any socket is opened) — 409 Conflict, matching the "current state precludes
            // this action" semantics rather than a validation (400) or server (500) error.
            return jsonResponse(Response.Status.CONFLICT, JSONObject().apply {
                put("status", "error")
                put("error", result.exceptionOrNull()?.message ?: "Provisioning repair failed")
            })
        }
        val repairResult = result.getOrThrow()
        val json = provisioningAuditJson(repairResult.audit)
        json.put("repairedKeys", JSONArray(repairResult.repairedKeys))
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMesh(): Response {
        val grouped = meshManager?.getMeshesGrouped()
        if (grouped != null) {
            grouped.put("status", "ok")
            grouped.put("meshPort", MeshDiscoveryManager.MESH_PORT)
            return jsonResponse(Response.Status.OK, grouped)
        }

        val peers = meshManager?.peersFlow?.value ?: emptyList()
        val json = JSONObject().apply {
            put("status", "ok")
            put("count", peers.size)
            put("meshPort", MeshDiscoveryManager.MESH_PORT)
            val arr = JSONArray()
            for (peer in peers) {
                arr.put(peer.toJson())
            }
            put("peers", arr)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshLibraryGet(session: IHTTPSession): Response {
        val meshId = session.parms["meshId"] ?: session.parms["mesh_id"] ?: SettingsStore.getLocalMeshId(context)
        val grouped = meshManager?.getMeshesGrouped()
        val meshesArray = grouped?.optJSONArray("meshes")

        val libraryList = if (meshesArray != null) {
            var foundArray: JSONArray? = null
            for (i in 0 until meshesArray.length()) {
                val m = meshesArray.getJSONObject(i)
                if (m.optString("id") == meshId) {
                    foundArray = m.optJSONArray("app_library")
                    break
                }
            }
            foundArray ?: JSONArray()
        } else {
            val stored = SettingsStore.getMeshAppLibrary(context, meshId)
            val arr = JSONArray()
            stored.values
                .filter { !AppVersionHelper.isExcludedAppPackage(it.packageName, context) }
                .sortedWith(
                    compareByDescending<SettingsStore.MeshAppConfig> { it.isSideloaded }
                        .thenBy { it.appName.lowercase() }
                )
                .forEach { arr.put(it.toJson()) }
            arr
        }

        val json = JSONObject().apply {
            put("status", "ok")
            put("mesh_id", meshId)
            put("library", libraryList)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshLibraryPost(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val meshId = body.optString("meshId", body.optString("mesh_id", SettingsStore.getLocalMeshId(context)))
        val appObj = body.optJSONObject("app") ?: body

        val pkg = appObj.optString("packageName", appObj.optString("package", ""))
        if (pkg.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing packageName")
            })
        }

        val config = SettingsStore.MeshAppConfig.fromJson(appObj)
        val newVer = SettingsStore.setMeshAppConfig(context, meshId, config)
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Updated library configuration for ${config.appName}")
            put("mesh_id", meshId)
            put("config_version", newVer)
            put("app", config.toJson())
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshConnect(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val ip = body.optString("ip", body.optString("seed", ""))
        if (ip.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing 'ip' or 'seed' parameter")
            })
        }

        val result = meshManager?.addPersistentConnection(ip, reciprocal = true)
        val normalized = result?.getOrNull() ?: ip
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Connecting to persistent connection $normalized")
            put("seed", normalized)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshHandshake(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val senderIp = session.remoteIpAddress ?: "127.0.0.1"
        val responseJson = if (meshManager != null) {
            meshManager.handleIncomingHandshake(body, senderIp)
        } else {
            val remoteSenderIp = body.optString("sender_ip", senderIp)
            val remotePort = body.optInt("sender_port", 2325)
            val remoteSeed = "$remoteSenderIp:$remotePort"
            SettingsStore.addPersistentConnection(context, remoteSeed)
            JSONObject()
        }
        responseJson.put("status", "ok")
        return jsonResponse(Response.Status.OK, responseJson)
    }

    private fun handleMeshConfigGet(): Response {
        val json = JSONObject().apply {
            put("status", "ok")
            put("config", SettingsStore.exportConfigJson(context))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshConfigSync(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val configJson = body.optJSONObject("config") ?: body
        val result = SettingsStore.importConfigJson(context, configJson)
        if (result.applied) {
            Logger.i("Applied incoming mesh config v${result.newVersion} via sync API from ${session.remoteIpAddress} (portChanged=${result.portChanged}, pwdChanged=${result.passwordChanged}, seedsChanged=${result.seedsChanged})")
            meshManager?.triggerBeacon()
        }
        val json = JSONObject().apply {
            put("status", "ok")
            put("applied", result.applied)
            put("old_version", result.oldVersion)
            put("new_version", result.newVersion)
            put("port_changed", result.portChanged)
            put("password_changed", result.passwordChanged)
            put("seeds_changed", result.seedsChanged)
            put("config_version", SettingsStore.getConfigVersion(context))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshPersistentConnectionsGet(): Response {
        val connections = SettingsStore.getPersistentConnections(context)
        val arr = JSONArray()
        connections.forEach { arr.put(it) }
        val json = JSONObject().apply {
            put("status", "ok")
            // Dual-emit: new key + old key for backward compatibility (1 cycle)
            put("persistent_connections", arr)
            put("seeds", arr)  // Deprecated: use persistent_connections
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshPersistentConnectionsModify(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val ip = body.optString("ip", body.optString("seed", body.optString("connection", session.parms["ip"] ?: session.parms["seed"] ?: session.parms["connection"] ?: "")))
        if (ip.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing persistent connection IP")
            })
        }

        // Determine operation: DELETE or /remove path = remove; POST = add
        val isRemove = session.method == NanoHTTPD.Method.DELETE || session.uri.endsWith("/remove")

        return if (isRemove) {
            val removed = meshManager?.removePersistentConnection(ip) ?: SettingsStore.removePersistentConnection(context, ip)
            val json = JSONObject().apply {
                put("status", "ok")
                put("removed", removed)
                put("message", "Removed persistent connection $ip")
            }
            jsonResponse(Response.Status.OK, json)
        } else {
            // POST = add
            val result = meshManager?.addPersistentConnection(ip, reciprocal = true)
            val normalized = result?.getOrNull() ?: ip
            meshManager?.syncConfigToMesh()
            val json = JSONObject().apply {
                put("status", "ok")
                put("message", "Added persistent connection $normalized")
                put("connection", normalized)
            }
            jsonResponse(Response.Status.OK, json)
        }
    }

    private fun handleMeshBeacon(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        meshManager?.triggerBeacon()
        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Mesh UDP beacon broadcast dispatched")
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshCreate(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val meshId = body.optString("meshId", "").trim().lowercase()
        val meshName = body.optString("meshName", "").trim()

        if (meshId.isBlank() || meshName.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "meshId and meshName are required")
            })
        }

        if (!meshId.matches(Regex("^[a-z0-9-]+$"))) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "meshId must contain only lowercase letters, numbers, and hyphens")
            })
        }

        // Add mesh to known templates (doesn't assign device)
        val added = SettingsStore.addKnownMesh(context, meshId, meshName)
        if (!added) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Mesh $meshId already exists")
            })
        }

        Logger.i("Mesh template created: $meshId ($meshName)")
        // Sync mesh templates to all devices
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Mesh created: $meshId")
            put("meshId", meshId)
            put("meshName", meshName)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshDelete(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val meshId = body.optString("meshId", "").trim().lowercase()

        if (meshId.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "meshId is required")
            })
        }

        if (meshId == "unmanaged") {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Cannot delete the Unmanaged mesh")
            })
        }

        val peerCount = meshManager?.peersFlow?.value?.count { it.meshId == meshId } ?: 0
        if (peerCount > 0) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Cannot delete a mesh with $peerCount assigned device(s)")
            })
        }

        val removed = SettingsStore.removeKnownMesh(context, meshId)
        if (!removed) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Mesh $meshId does not exist")
            })
        }

        Logger.i("Mesh template deleted: $meshId")
        // Sync deletion (tombstone) to all devices
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Mesh deleted: $meshId")
            put("meshId", meshId)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshUpdate(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val meshId = body.optString("meshId", "").trim()
        val meshName = body.optString("meshName", "").trim()

        if (meshId.isBlank() || meshName.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "meshId and meshName are required")
            })
        }

        // Only allow updating if this is the local mesh
        if (meshId != SettingsStore.getLocalMeshId(context)) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Can only update the local mesh name")
            })
        }

        SettingsStore.setLocalMeshName(context, meshName)
        SettingsStore.updateConfigVersion(context)
        Logger.i("Mesh updated: $meshId name -> $meshName")

        // Sync to fleet
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Mesh name updated")
            put("meshId", meshId)
            put("meshName", meshName)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handlePeerUpdate(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val ip = body.optString("ip", "")
        val port = body.optInt("port", 2325)
        val tag = body.optString("tag", "")
        val url = body.optString("url", "")
        val pkg = body.optString("package", body.optString("packageName", ""))
        val force = body.optBoolean("force", true)

        if (ip.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing peer IP address")
            })
        }

        scope.launch {
            try {
                val req = buildPeerUpdateRequest(ip, port, tag, url, pkg, force)
                httpClient.newCall(req).execute().use { logRelayOutcome(it, ip, port, "update") }
            } catch (e: Exception) {
                Logger.e("Error dispatching peer update to $ip:$port", e)
            }
        }

        val json = JSONObject().apply {
            put("status", "accepted")
            put("message", "Dispatched update sequence to peer $ip:$port")
        }
        return jsonResponse(Response.Status.ACCEPTED, json)
    }

    // API-BEHAVE-036 (gitea#63): a non-2xx relay response was previously discarded entirely
    // (`.close()` with no status check), so a caller who thinks a peer relay "succeeded" (this
    // endpoint always returns 202 immediately, before the relay even runs) had no way to learn a
    // password-protected peer actually rejected it -- surfaced here via /logs, the existing
    // observability path for this fire-and-forget dispatch.
    internal fun logRelayOutcome(response: okhttp3.Response, ip: String, port: Int, action: String) {
        if (!response.isSuccessful) {
            Logger.w("Peer relay '$action' to $ip:$port was rejected: HTTP ${response.code} -- " +
                "this device's auth token does not necessarily validate against that peer's own " +
                "admin password (auth_secret is deliberately never synced mesh-wide, SET-BEHAVE-007)")
        } else {
            Logger.i("Peer relay '$action' to $ip:$port succeeded: HTTP ${response.code}")
        }
    }

    // [API-BEHAVE-022] Builds the outbound relay request for a peer-directed update. Attaches a
    // freshly minted, short-lived bearer token whenever this device has a password configured, in
    // case the target peer happens to share the same admin password (and therefore, coincidentally,
    // could derive the same auth_secret) -- but API-BEHAVE-036/gitea#63: auth_secret is deliberately
    // NEVER synced mesh-wide (SET-BEHAVE-007), so a token minted here is expected to be rejected by
    // any peer with its own independently-set password. This is not a real cross-device credential;
    // logRelayOutcome() surfaces the resulting 401 via /logs instead of silently swallowing it.
    internal fun buildPeerUpdateRequest(
        ip: String,
        port: Int,
        tag: String,
        url: String,
        pkg: String,
        force: Boolean
    ): Request {
        val queryParams = mutableListOf("force=$force")
        if (tag.isNotBlank()) queryParams.add("tag=${URLEncoder.encode(tag, "UTF-8")}")
        if (url.isNotBlank()) queryParams.add("url=${URLEncoder.encode(url, "UTF-8")}")
        if (pkg.isNotBlank()) queryParams.add("package=${URLEncoder.encode(pkg, "UTF-8")}")
        val updateUrl = "http://$ip:$port/update?${queryParams.joinToString("&")}"

        val builder = Request.Builder()
            .url(updateUrl)
            .post(ByteArray(0).toRequestBody(null, 0, 0))
        if (SettingsStore.isPasswordSet(context)) {
            builder.header("Authorization", "Bearer ${SettingsStore.generateToken(context, ttlSeconds = 60)}")
        }
        return builder.build()
    }

    private fun handlePeerAdbToggle(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val ip = body.optString("ip", "")
        val port = body.optInt("port", 2325)

        if (ip.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing peer IP address")
            })
        }

        scope.launch {
            try {
                val builder = Request.Builder()
                    .url("http://$ip:$port/adb/toggle")
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                if (SettingsStore.isPasswordSet(context)) {
                    builder.header("Authorization", "Bearer ${SettingsStore.generateToken(context, ttlSeconds = 60)}")
                }
                httpClient.newCall(builder.build()).execute().use { logRelayOutcome(it, ip, port, "adb-toggle") }
            } catch (e: Exception) {
                Logger.e("Failed to toggle ADB on remote peer $ip", e)
            }
        }

        val json = JSONObject().apply {
            put("status", "accepted")
            put("message", "Dispatched ADB toggle to peer $ip:$port")
        }
        return jsonResponse(Response.Status.ACCEPTED, json)
    }

    private fun handleLogs(): Response {
        val logs = Logger.getRecentLogs()
        val json = JSONObject().apply {
            put("status", "ok")
            put("logs", JSONArray(logs))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleLogsClear(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        Logger.clear()
        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Log buffer cleared")
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun addStandardHeaders(response: Response) {
        response.addHeader("Connection", "close")
    }


    private fun jsonResponse(
        status: Response.Status,
        obj: JSONObject,
        cookies: Map<String, String>? = null
    ): Response {
        val res = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString(2))
        addStandardHeaders(res)
        cookies?.forEach { (k, v) ->
            if (v.isBlank()) {
                res.addHeader("Set-Cookie", "$k=; Path=/; Max-Age=0; SameSite=Lax")
            } else {
                res.addHeader("Set-Cookie", "$k=$v; Path=/; Max-Age=604800; SameSite=Lax")
            }
        }
        return res
    }
}

