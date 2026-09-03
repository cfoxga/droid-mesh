package com.cfox.droidmesh.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Persisted user-facing configuration for DroidMesh / KSU.
 * Manages auto-update toggle, web admin password, web server settings,
 * mesh parameters, and mesh-wide configuration synchronization.
 */
object SettingsStore {
    /** Public so other components (e.g. CpuStatsHelper) reference the same prefs file rather
     *  than duplicating the literal name. */
    const val PREFS_NAME = "droid_mesh_settings"
    private const val KEY_CONFIG_VERSION = "config_version"
    private const val KEY_WEB_SERVER_PORT = "web_server_port"
    private const val KEY_WEB_PASSWORD_HASH = "web_password_hash"
    private const val KEY_WEB_PASSWORD_SALT = "web_password_salt"
    // SET-BEHAVE-006: PBKDF2WithHmacSHA256 params for admin password hashing. Iteration count
    // travels with each hash (encodePbkdf2Hash) so raising this later doesn't invalidate
    // existing hashes.
    private const val PBKDF2_HASH_PREFIX = "pbkdf2$"
    private const val PBKDF2_ITERATIONS = 210_000
    private const val PBKDF2_KEY_LENGTH_BITS = 256
    private const val KEY_AUTH_SECRET = "auth_secret"
    private const val KEY_LOCAL_MESH_ID = "local_mesh_id"
    private const val KEY_LOCAL_MESH_NAME = "local_mesh_name"
    private const val KEY_PERSISTENT_CONNECTIONS = "persistent_connections"
    private const val KEY_CUSTOM_DEVICE_NAME = "custom_device_name"
    private const val KEY_KNOWN_MESHES = "known_meshes"
    private const val KEY_DELETED_MESHES = "deleted_meshes"
    private const val KEY_DELETED_CONNECTIONS = "deleted_connections"
    private const val KEY_MESH_APP_LIBRARIES = "mesh_app_libraries"

    private const val DEFAULT_WEB_SERVER_PORT = 2325

    data class MeshAppConfig(
        val packageName: String,
        val appName: String,
        val managed: Boolean = false,
        val autoInstall: Boolean = false,
        val targetVersion: String = "latest",
        val autoUpdate: Boolean = false,
        val isSideloaded: Boolean = false,
        /** Direct APK download URL for auto-install. Empty = no automatic download. */
        val downloadUrl: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("packageName", packageName)
            put("appName", appName)
            put("managed", managed)
            put("autoInstall", autoInstall)
            put("targetVersion", targetVersion)
            put("autoUpdate", autoUpdate)
            put("isSideloaded", isSideloaded)
            put("downloadUrl", downloadUrl)
        }

        companion object {
            fun fromJson(json: JSONObject): MeshAppConfig {
                val pkg = json.optString("packageName", json.optString("package", ""))
                val name = json.optString("appName", json.optString("name", pkg))
                val isSideload = if (json.has("isSideloaded")) json.optBoolean("isSideloaded") else com.cfox.droidmesh.installer.AppVersionHelper.isSideloadedApp(pkg)
                val downloadUrl = json.optString("downloadUrl", "")
                // SET-BEHAVE-005 / APP-BEHAVE-007: a SIDELOADED entry can never be Managed without
                // a downloadUrl. Legacy/synced sideloaded data carrying managed=true with a blank
                // downloadUrl self-heals to managed=false here rather than surfacing as
                // managed-but-unusable. A store-origin entry never has a downloadUrl to give
                // (DroidMesh doesn't download/install Play Store APKs directly) and is never gated.
                val managed = json.optBoolean("managed", false) && (!isSideload || downloadUrl.isNotBlank())
                return MeshAppConfig(
                    packageName = pkg,
                    appName = if (name.isNotBlank()) name else pkg,
                    managed = managed,
                    autoInstall = json.optBoolean("autoInstall", false),
                    targetVersion = json.optString("targetVersion", "latest"),
                    autoUpdate = json.optBoolean("autoUpdate", false),
                    isSideloaded = isSideload,
                    downloadUrl = downloadUrl
                )
            }
        }
    }

    data class ConfigImportResult(
        val applied: Boolean,
        val oldVersion: Long,
        val newVersion: Long,
        val portChanged: Boolean = false,
        val passwordChanged: Boolean = false,
        val seedsChanged: Boolean = false,
        val libraryChanged: Boolean = false,
        val meshesChanged: Boolean = false
    )

    fun interface OnConfigChangeListener {
        fun onConfigChanged(result: ConfigImportResult)
    }

    private val listeners = CopyOnWriteArraySet<OnConfigChangeListener>()

    fun addConfigChangeListener(listener: OnConfigChangeListener) {
        listeners.add(listener)
    }

    fun removeConfigChangeListener(listener: OnConfigChangeListener) {
        listeners.remove(listener)
    }

    fun notifyListeners(result: ConfigImportResult) {
        for (l in listeners) {
            try {
                l.onConfigChanged(result)
            } catch (_: Exception) {}
        }
    }

    fun getConfigVersion(context: Context): Long =
        prefs(context).getLong(KEY_CONFIG_VERSION, 0L)

    fun updateConfigVersion(context: Context): Long {
        val current = getConfigVersion(context)
        val next = maxOf(System.currentTimeMillis(), current + 1L)
        prefs(context).edit().putLong(KEY_CONFIG_VERSION, next).apply()
        return next
    }

    fun getWebServerPort(context: Context): Int =
        prefs(context).getInt(KEY_WEB_SERVER_PORT, DEFAULT_WEB_SERVER_PORT)

    fun setWebServerPort(context: Context, port: Int) {
        val validPort = if (port in 1024..65535) port else DEFAULT_WEB_SERVER_PORT
        val prev = getWebServerPort(context)
        if (prev == validPort) return
        val ver = maxOf(System.currentTimeMillis(), getConfigVersion(context) + 1L)
        val editor = prefs(context).edit()
        editor.putInt(KEY_WEB_SERVER_PORT, validPort)
        editor.putLong(KEY_CONFIG_VERSION, ver)
        editor.apply()
        notifyListeners(
            ConfigImportResult(
                applied = true,
                oldVersion = ver - 1,
                newVersion = ver,
                portChanged = true
            )
        )
    }


    fun getDefaultMeshId(@Suppress("UNUSED_PARAMETER") context: Context): String {
        // All devices start in "unmanaged" mesh until explicitly assigned
        return "unmanaged"
    }

    fun getDefaultMeshName(@Suppress("UNUSED_PARAMETER") context: Context): String {
        // "Unmanaged" is the default mesh for unconfigured devices
        return "Unmanaged"
    }

    fun getLocalMeshId(context: Context): String {
        val saved = prefs(context).getString(KEY_LOCAL_MESH_ID, null)
        return if (!saved.isNullOrBlank()) saved else getDefaultMeshId(context)
    }

    fun setLocalMeshId(context: Context, meshId: String) {
        prefs(context).edit().putString(KEY_LOCAL_MESH_ID, meshId.trim()).apply()
    }

    fun getLocalMeshName(context: Context): String {
        val saved = prefs(context).getString(KEY_LOCAL_MESH_NAME, null)
        return if (!saved.isNullOrBlank()) saved else getDefaultMeshName(context)
    }

    fun setLocalMeshName(context: Context, meshName: String) {
        prefs(context).edit().putString(KEY_LOCAL_MESH_NAME, meshName.trim()).apply()
    }

    fun getCustomDeviceName(context: Context): String {
        val saved = prefs(context).getString(KEY_CUSTOM_DEVICE_NAME, null)
        return saved ?: ""
    }

    fun setCustomDeviceName(context: Context, deviceName: String) {
        prefs(context).edit().putString(KEY_CUSTOM_DEVICE_NAME, deviceName.trim()).apply()
    }

    data class MeshTemplate(val id: String, val name: String)

    /** [MESH-BEHAVE-009/010] A record that mesh [id] was deleted at [deletedAt] (epoch ms). */
    data class MeshTombstone(val id: String, val deletedAt: Long)

    fun getDeletedMeshes(context: Context): List<MeshTombstone> {
        val json = prefs(context).getString(KEY_DELETED_MESHES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i)
                val id = obj?.optString("id") ?: ""
                if (id.isNotEmpty()) MeshTombstone(id, obj.optLong("deletedAt", 0L)) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setDeletedMeshes(context: Context, tombstones: List<MeshTombstone>) {
        val json = JSONArray().apply {
            tombstones.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("deletedAt", t.deletedAt)
                })
            }
        }
        prefs(context).edit().putString(KEY_DELETED_MESHES, json.toString()).apply()
    }

    fun getKnownMeshes(context: Context): List<MeshTemplate> {
        val json = prefs(context).getString(KEY_KNOWN_MESHES, null) ?: return listOf(
            MeshTemplate("unmanaged", "Unmanaged")
        )
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    val id = obj.optString("id")
                    val name = obj.optString("name")
                    if (id.isNotEmpty()) MeshTemplate(id, name) else null
                }
                else null
            }
        } catch (e: Exception) {
            listOf(MeshTemplate("unmanaged", "Unmanaged"))
        }
    }

    fun setKnownMeshes(context: Context, meshes: List<MeshTemplate>) {
        val json = JSONArray().apply {
            meshes.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                })
            }
        }
        prefs(context).edit().putString(KEY_KNOWN_MESHES, json.toString()).apply()
    }

    fun addKnownMesh(context: Context, meshId: String, meshName: String): Boolean {
        val cleanId = meshId.trim().lowercase()
        val cleanName = meshName.trim()
        if (cleanId.isBlank() || cleanName.isBlank()) return false

        val current = getKnownMeshes(context).toMutableList()
        // Check if already exists
        if (current.any { it.id == cleanId }) return false

        current.add(MeshTemplate(cleanId, cleanName))
        setKnownMeshes(context, current)

        // [MESH-BEHAVE-010] Explicit recreation wins over a stale tombstone: clear it locally so
        // this addition isn't immediately re-deleted by the tombstone filter on the next merge.
        val tombstones = getDeletedMeshes(context)
        if (tombstones.any { it.id == cleanId }) {
            setDeletedMeshes(context, tombstones.filterNot { it.id == cleanId })
        }

        updateConfigVersion(context)
        return true
    }

    /**
     * [MESH-BEHAVE-009] Removes a mesh template, recording a tombstone so the deletion propagates
     * across the fleet instead of being silently resurrected by the known_meshes union merge.
     * Rejects "unmanaged" (permanently protected) and unknown ids. Caller (REST layer) is
     * responsible for the peer_count > 0 check, since live peer assignment isn't known here.
     */
    fun removeKnownMesh(context: Context, meshId: String): Boolean {
        val cleanId = meshId.trim().lowercase()
        if (cleanId.isBlank() || cleanId == "unmanaged") return false

        val current = getKnownMeshes(context)
        if (current.none { it.id == cleanId }) return false

        val oldVer = getConfigVersion(context)
        setKnownMeshes(context, current.filterNot { it.id == cleanId })

        val ver = updateConfigVersion(context)
        val tombstones = getDeletedMeshes(context).filterNot { it.id == cleanId }
        setDeletedMeshes(context, tombstones + MeshTombstone(cleanId, ver))

        notifyListeners(
            ConfigImportResult(
                applied = true,
                oldVersion = oldVer,
                newVersion = ver,
                meshesChanged = true
            )
        )
        return true
    }

    /** [MESH-BEHAVE-011/012] A record that persistent connection [connection] was removed at [deletedAt] (epoch ms). */
    data class ConnectionTombstone(val connection: String, val deletedAt: Long)

    fun getDeletedConnections(context: Context): List<ConnectionTombstone> {
        val json = prefs(context).getString(KEY_DELETED_CONNECTIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i)
                val conn = obj?.optString("connection") ?: ""
                if (conn.isNotEmpty()) ConnectionTombstone(conn, obj.optLong("deletedAt", 0L)) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setDeletedConnections(context: Context, tombstones: List<ConnectionTombstone>) {
        val json = JSONArray().apply {
            tombstones.forEach { t ->
                put(JSONObject().apply {
                    put("connection", t.connection)
                    put("deletedAt", t.deletedAt)
                })
            }
        }
        prefs(context).edit().putString(KEY_DELETED_CONNECTIONS, json.toString()).apply()
    }

    fun getPersistentConnections(context: Context): Set<String> {
        val connections = prefs(context).getStringSet(KEY_PERSISTENT_CONNECTIONS, emptySet()) ?: emptySet()
        return connections.toSet()
    }

    fun setPersistentConnections(context: Context, connections: Set<String>) {
        val prev = getPersistentConnections(context)
        if (prev == connections) return
        val ver = maxOf(System.currentTimeMillis(), getConfigVersion(context) + 1L)
        val editor = prefs(context).edit()
        editor.putStringSet(KEY_PERSISTENT_CONNECTIONS, connections)
        editor.putLong(KEY_CONFIG_VERSION, ver)
        editor.apply()
        notifyListeners(
            ConfigImportResult(
                applied = true,
                oldVersion = ver - 1,
                newVersion = ver,
                seedsChanged = true
            )
        )
    }

    fun addPersistentConnection(context: Context, connection: String): Boolean {
        val cleanConnection = connection.trim()
        if (cleanConnection.isBlank()) return false
        val current = getPersistentConnections(context).toMutableSet()
        val added = current.add(cleanConnection)
        if (added) {
            setPersistentConnections(context, current)
        }

        // [MESH-BEHAVE-012] Explicit add always wins over a stale local tombstone, mirroring
        // addKnownMesh -- otherwise a re-add would be immediately re-deleted by the next merge.
        val tombstones = getDeletedConnections(context)
        if (tombstones.any { it.connection == cleanConnection }) {
            setDeletedConnections(context, tombstones.filterNot { it.connection == cleanConnection })
        }
        return added
    }

    /**
     * [MESH-BEHAVE-012] Removes a persistent connection, recording a tombstone so the removal
     * propagates across the fleet via config sync instead of being silently resurrected by the
     * union merge in [importConfigJson].
     */
    fun removePersistentConnection(context: Context, connection: String): Boolean {
        val cleanConnection = connection.trim()
        val current = getPersistentConnections(context)
        if (!current.contains(cleanConnection)) return false

        val oldVer = getConfigVersion(context)
        prefs(context).edit().putStringSet(KEY_PERSISTENT_CONNECTIONS, current - cleanConnection).apply()

        val ver = updateConfigVersion(context)
        val tombstones = getDeletedConnections(context).filterNot { it.connection == cleanConnection }
        setDeletedConnections(context, tombstones + ConnectionTombstone(cleanConnection, ver))

        notifyListeners(
            ConfigImportResult(
                applied = true,
                oldVersion = oldVer,
                newVersion = ver,
                seedsChanged = true
            )
        )
        return true
    }

    // Backward compatibility: map old cross_vlan_seeds to persistent_connections
    @Deprecated("Use getPersistentConnections")
    fun getCrossVlanSeeds(context: Context): Set<String> = getPersistentConnections(context)

    @Deprecated("Use setPersistentConnections")
    fun setCrossVlanSeeds(context: Context, seeds: Set<String>) = setPersistentConnections(context, seeds)

    @Deprecated("Use addPersistentConnection")
    fun addCrossVlanSeed(context: Context, seed: String): Boolean = addPersistentConnection(context, seed)

    @Deprecated("Use removePersistentConnection")
    fun removeCrossVlanSeed(context: Context, seed: String): Boolean = removePersistentConnection(context, seed)

    fun isPasswordSet(context: Context): Boolean =
        !prefs(context).getString(KEY_WEB_PASSWORD_HASH, null).isNullOrBlank()

    fun setPassword(context: Context, password: String): Boolean {
        val editor = prefs(context).edit()
        val ver = maxOf(System.currentTimeMillis(), getConfigVersion(context) + 1L)
        editor.putLong(KEY_CONFIG_VERSION, ver)

        if (password.isBlank()) {
            editor.remove(KEY_WEB_PASSWORD_HASH)
            editor.remove(KEY_WEB_PASSWORD_SALT)
            editor.apply()
            notifyListeners(
                ConfigImportResult(
                    applied = true,
                    oldVersion = ver - 1,
                    newVersion = ver,
                    passwordChanged = true
                )
            )
            return true
        }

        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = hashPasswordPbkdf2(password, salt)

        editor.putString(KEY_WEB_PASSWORD_SALT, bytesToHex(salt))
        editor.putString(KEY_WEB_PASSWORD_HASH, encodePbkdf2Hash(PBKDF2_ITERATIONS, hash))
        // Invalidate previous auth tokens on password change by cycling auth secret
        val newSecret = ByteArray(32)
        SecureRandom().nextBytes(newSecret)
        editor.putString(KEY_AUTH_SECRET, bytesToHex(newSecret))
        editor.apply()

        notifyListeners(
            ConfigImportResult(
                applied = true,
                oldVersion = ver - 1,
                newVersion = ver,
                passwordChanged = true
            )
        )
        return true
    }

    fun verifyPassword(context: Context, password: String): Boolean {
        if (!isPasswordSet(context)) return true
        val p = prefs(context)
        val saltHex = p.getString(KEY_WEB_PASSWORD_SALT, null) ?: return false
        val hashHex = p.getString(KEY_WEB_PASSWORD_HASH, null) ?: return false
        val salt = hexToBytes(saltHex)

        if (hashHex.startsWith(PBKDF2_HASH_PREFIX)) {
            val parts = hashHex.split("$")
            if (parts.size != 3) return false
            val iterations = parts[1].toIntOrNull() ?: return false
            val expectedHash = hexToBytes(parts[2])
            val actualHash = hashPasswordPbkdf2(password, salt, iterations)
            return MessageDigest.isEqual(expectedHash, actualHash)
        }

        // Legacy single-round SHA-256(salt||password) hash (SET-BEHAVE-001, superseded) --
        // still verified as written, but migrated to PBKDF2 in place on a successful match
        // (SET-BEHAVE-006) so the admin never has to reset a password just to get the
        // stronger hash.
        val expectedLegacyHash = hexToBytes(hashHex)
        val actualLegacyHash = hashPasswordLegacySha256(password, salt)
        val matches = MessageDigest.isEqual(expectedLegacyHash, actualLegacyHash)
        if (matches) {
            val migratedHash = hashPasswordPbkdf2(password, salt)
            p.edit().putString(KEY_WEB_PASSWORD_HASH, encodePbkdf2Hash(PBKDF2_ITERATIONS, migratedHash)).apply()
        }
        return matches
    }

    fun clearPassword(context: Context) {
        val ver = maxOf(System.currentTimeMillis(), getConfigVersion(context) + 1L)
        prefs(context).edit()
            .remove(KEY_WEB_PASSWORD_HASH)
            .remove(KEY_WEB_PASSWORD_SALT)
            .putLong(KEY_CONFIG_VERSION, ver)
            .apply()
        notifyListeners(
            ConfigImportResult(
                applied = true,
                oldVersion = ver - 1,
                newVersion = ver,
                passwordChanged = true
            )
        )
    }

    fun getAllMeshAppLibraries(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_MESH_APP_LIBRARIES, null)
        return if (!raw.isNullOrBlank()) {
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
    }

    fun getMeshAppLibrary(context: Context, meshId: String): Map<String, MeshAppConfig> {
        val root = getAllMeshAppLibraries(context)
        val meshObj = root.optJSONObject(meshId)
        val result = mutableMapOf<String, MeshAppConfig>()

        // No app is ever hardcoded into the App Library's defaults for any mesh partition
        // (SET-BEHAVE-005) — every entry is either discovered from actual installed-app
        // inventory or added explicitly by the admin.
        if (meshObj != null) {
            val keys = meshObj.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                if (com.cfox.droidmesh.installer.AppVersionHelper.isExcludedAppPackage(pkg, context)) {
                    continue
                }
                val appJson = meshObj.optJSONObject(pkg)
                if (appJson != null) {
                    val config = MeshAppConfig.fromJson(appJson)
                    result[config.packageName] = config
                }
            }
        }

        // Always ensure DroidMesh companion is excluded from the library
        result.remove("com.cfox.droidmesh")
        result.remove(context.packageName)
        return result
    }

    fun setMeshAppConfig(context: Context, meshId: String, appConfig: MeshAppConfig): Long {
        // SET-BEHAVE-005 / APP-BEHAVE-007: a SIDELOADED entry can never be persisted as Managed
        // without a downloadUrl. Coerce (never throw) rather than reject the write outright. A
        // store-origin entry is exempt — it will never have a downloadUrl, and Managed there is
        // a plain admin-curated toggle, not a release-source gate.
        val safeConfig = if (appConfig.managed && appConfig.isSideloaded && appConfig.downloadUrl.trim().isBlank()) {
            appConfig.copy(managed = false)
        } else {
            appConfig
        }
        val root = getAllMeshAppLibraries(context)
        val meshObj = root.optJSONObject(meshId) ?: JSONObject()
        meshObj.put(safeConfig.packageName, safeConfig.toJson())
        root.put(meshId, meshObj)

        val ver = maxOf(System.currentTimeMillis(), getConfigVersion(context) + 1L)
        val editor = prefs(context).edit()
        editor.putString(KEY_MESH_APP_LIBRARIES, root.toString())
        editor.putLong(KEY_CONFIG_VERSION, ver)
        editor.apply()

        notifyListeners(
            ConfigImportResult(
                applied = true,
                oldVersion = ver - 1,
                newVersion = ver,
                libraryChanged = true
            )
        )
        return ver
    }

    fun setMeshAppLibrary(context: Context, meshId: String, library: Map<String, MeshAppConfig>): Long {
        val root = getAllMeshAppLibraries(context)
        val meshObj = JSONObject()
        for ((pkg, config) in library) {
            meshObj.put(pkg, config.toJson())
        }
        root.put(meshId, meshObj)

        val ver = maxOf(System.currentTimeMillis(), getConfigVersion(context) + 1L)
        val editor = prefs(context).edit()
        editor.putString(KEY_MESH_APP_LIBRARIES, root.toString())
        editor.putLong(KEY_CONFIG_VERSION, ver)
        editor.apply()

        notifyListeners(
            ConfigImportResult(
                applied = true,
                oldVersion = ver - 1,
                newVersion = ver,
                libraryChanged = true
            )
        )
        return ver
    }

    fun exportConfigJson(context: Context, knownPeersJson: JSONArray? = null): JSONObject = JSONObject().apply {
        put("config_version", getConfigVersion(context))
        put("web_server_port", getWebServerPort(context))
        // [SET-BEHAVE-007] auth_secret/web_password_hash/web_password_salt are deliberately never
        // included here. This payload is gossiped over unauthenticated cleartext HTTP between mesh
        // peers (MeshDiscoveryManager push/pull/handshake, and GET /api/mesh/config directly) -- see
        // gitea#31/#32/#34. Credential material stays local to each device.
        val connectionsArr = JSONArray()
        getPersistentConnections(context).forEach { connectionsArr.put(it) }
        put("persistent_connections", connectionsArr)
        // For backward compatibility, also include under old key
        put("cross_vlan_seeds", connectionsArr)
        // [MESH-BEHAVE-012] Tombstones so persistent-connection removal propagates instead of
        // being resurrected by the union merge, mirroring deleted_meshes below.
        val deletedConnectionsArr = JSONArray()
        getDeletedConnections(context).forEach { t ->
            deletedConnectionsArr.put(JSONObject().apply {
                put("connection", t.connection)
                put("deletedAt", t.deletedAt)
            })
        }
        put("deleted_connections", deletedConnectionsArr)
        put("mesh_app_libraries", getAllMeshAppLibraries(context))
        // Include known mesh templates
        val meshesArr = JSONArray()
        getKnownMeshes(context).forEach { m ->
            meshesArr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
            })
        }
        put("known_meshes", meshesArr)
        // [MESH-BEHAVE-010] Tombstones so mesh deletion propagates instead of resurrecting.
        val deletedArr = JSONArray()
        getDeletedMeshes(context).forEach { t ->
            deletedArr.put(JSONObject().apply {
                put("id", t.id)
                put("deletedAt", t.deletedAt)
            })
        }
        put("deleted_meshes", deletedArr)
        // Include known peers for recovery after power loss
        if (knownPeersJson != null) {
            put("known_peers", knownPeersJson)
        }
    }

    fun importConfigJson(context: Context, json: JSONObject): ConfigImportResult {
        val incomingVersion = json.optLong("config_version", 0L)
        val currentVersion = getConfigVersion(context)

        if (incomingVersion <= 0L || incomingVersion <= currentVersion) {
            return ConfigImportResult(
                applied = false,
                oldVersion = currentVersion,
                newVersion = incomingVersion
            )
        }

        val editor = prefs(context).edit()
        var portChanged = false
        var passwordChanged = false
        var seedsChanged = false
        var libraryChanged = false
        var meshesChanged = false

        if (json.has("web_server_port")) {
            val port = json.getInt("web_server_port")
            if (port in 1024..65535 && getWebServerPort(context) != port) {
                editor.putInt(KEY_WEB_SERVER_PORT, port)
                portChanged = true
            }
        }

        // [SET-BEHAVE-007] web_password_hash/web_password_salt/auth_secret are deliberately never
        // read from an incoming config, from any source (mesh sync, handshake, or otherwise) --
        // gitea#32 showed a higher config_version with attacker-supplied credentials in this block
        // let a remote caller overwrite and mesh-propagate its own admin password and HMAC signing
        // secret with zero auth. Credential material is local-only now; see exportConfigJson above.

        // [MESH-BEHAVE-012] Synchronize persistent-connection deletion tombstones first: union of
        // local + incoming, keeping the max deletedAt per connection. Mirrors deleted_meshes below
        // -- this is what makes a removal durable across the union merge instead of looking
        // identical to "peer hasn't heard about this connection yet".
        val incomingConnectionTombstones = mutableListOf<ConnectionTombstone>()
        val connectionTombstonesArr = json.optJSONArray("deleted_connections")
        if (connectionTombstonesArr != null) {
            for (i in 0 until connectionTombstonesArr.length()) {
                val obj = connectionTombstonesArr.optJSONObject(i) ?: continue
                val conn = obj.optString("connection")
                if (conn.isNotEmpty()) incomingConnectionTombstones.add(ConnectionTombstone(conn, obj.optLong("deletedAt", 0L)))
            }
        }
        val currentConnectionTombstones = getDeletedConnections(context)
        val mergedConnectionTombstones = (currentConnectionTombstones + incomingConnectionTombstones)
            .groupBy { it.connection }
            .map { (conn, versions) -> ConnectionTombstone(conn, versions.maxOf { it.deletedAt }) }
        val tombstonedConnections = mergedConnectionTombstones.map { it.connection }.toSet()
        if (mergedConnectionTombstones.toSet() != currentConnectionTombstones.toSet()) {
            setDeletedConnections(context, mergedConnectionTombstones)
            seedsChanged = true
        }

        // [MESH-BEHAVE-011] Synchronize persistent connections (handles both old "cross_vlan_seeds"
        // and new "persistent_connections" keys) as a tombstone-filtered union merge, not a flat
        // overwrite -- an import that omits a connection this device already knows about must not
        // delete it, so a reciprocally-learned connection can't be clobbered by an unrelated,
        // higher-config_version push from a third device that never knew about it. This is also
        // what lets IP address lists gossip transitively: startPersistentConnectionSyncer re-reads
        // getPersistentConnections() every cycle, so a connection learned purely through this merge
        // is picked up and polled automatically without requiring an explicit handshake.
        var connectionsArr = json.optJSONArray("persistent_connections")
        if (connectionsArr == null) {
            connectionsArr = json.optJSONArray("cross_vlan_seeds")
        }
        val currentConnections = getPersistentConnections(context)
        if (connectionsArr != null) {
            val incomingConnections = mutableSetOf<String>()
            for (i in 0 until connectionsArr.length()) {
                val s = connectionsArr.optString(i, "").trim()
                if (s.isNotBlank()) incomingConnections.add(s)
            }
            val merged = (currentConnections + incomingConnections).filterNot { it in tombstonedConnections }.toSet()
            if (merged != currentConnections) {
                editor.putStringSet(KEY_PERSISTENT_CONNECTIONS, merged)
                seedsChanged = true
            }
        } else if (tombstonedConnections.isNotEmpty()) {
            // No incoming persistent_connections this round, but a newly-merged tombstone may
            // still apply to what's stored locally (e.g. a delete-only sync payload).
            val filtered = currentConnections.filterNot { it in tombstonedConnections }.toSet()
            if (filtered != currentConnections) {
                editor.putStringSet(KEY_PERSISTENT_CONNECTIONS, filtered)
                seedsChanged = true
            }
        }

        // Restore known peers for recovery after power loss (optional, best-effort)
        // The known_peers list helps bootstrap mesh discovery by providing last-known peer metadata
        if (json.has("known_peers")) {
            val knownPeersArr = json.optJSONArray("known_peers")
            if (knownPeersArr != null && knownPeersArr.length() > 0) {
                // Store known peers for MeshDiscoveryManager to restore on startup
                editor.putString("known_peers_json", knownPeersArr.toString())
            }
        }

        // Synchronize mesh app libraries. Re-serialized through MeshAppConfig.fromJson/toJson
        // per entry rather than stored as raw incoming JSON, so SET-BEHAVE-005 (an entry can
        // never be Managed without a downloadUrl) holds at the persistence layer itself — not
        // only by coincidence of every current reader re-applying the same coercion. A peer
        // that sends managed:true with a blank downloadUrl can no longer durably persist or
        // re-propagate that invalid combination through sync.
        if (json.has("mesh_app_libraries")) {
            val librariesObj = json.optJSONObject("mesh_app_libraries")
            if (librariesObj != null) {
                val sanitizedLibraries = JSONObject()
                val meshIds = librariesObj.keys()
                while (meshIds.hasNext()) {
                    val meshKey = meshIds.next()
                    val meshEntries = librariesObj.optJSONObject(meshKey) ?: continue
                    val sanitizedMesh = JSONObject()
                    val pkgKeys = meshEntries.keys()
                    while (pkgKeys.hasNext()) {
                        val pkgKey = pkgKeys.next()
                        val entryObj = meshEntries.optJSONObject(pkgKey) ?: continue
                        sanitizedMesh.put(pkgKey, MeshAppConfig.fromJson(entryObj).toJson())
                    }
                    sanitizedLibraries.put(meshKey, sanitizedMesh)
                }
                val currentLibraries = getAllMeshAppLibraries(context).toString()
                val incomingLibraries = sanitizedLibraries.toString()
                if (currentLibraries != incomingLibraries) {
                    editor.putString(KEY_MESH_APP_LIBRARIES, incomingLibraries)
                    libraryChanged = true
                }
            }
        }

        // [MESH-BEHAVE-010] Synchronize mesh deletion tombstones first: union of local + incoming,
        // keeping the max deletedAt per id. This is what makes deletion durable across the union
        // merge below instead of looking identical to "peer hasn't heard about this mesh yet".
        val incomingTombstones = mutableListOf<MeshTombstone>()
        val tombstonesArr = json.optJSONArray("deleted_meshes")
        if (tombstonesArr != null) {
            for (i in 0 until tombstonesArr.length()) {
                val obj = tombstonesArr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isNotEmpty()) incomingTombstones.add(MeshTombstone(id, obj.optLong("deletedAt", 0L)))
            }
        }
        val currentTombstones = getDeletedMeshes(context)
        val mergedTombstones = (currentTombstones + incomingTombstones)
            .groupBy { it.id }
            .map { (id, versions) -> MeshTombstone(id, versions.maxOf { it.deletedAt }) }
        val tombstonedIds = mergedTombstones.map { it.id }.toSet()
        if (mergedTombstones.toSet() != currentTombstones.toSet()) {
            setDeletedMeshes(context, mergedTombstones)
            meshesChanged = true
        }

        // Synchronize known mesh templates (union-preserve merge), then filter out anything
        // present in the merged tombstone set above so a deletion actually sticks.
        val currentMeshes = getKnownMeshes(context)
        val meshesArr = json.optJSONArray("known_meshes")
        if (meshesArr != null) {
            val incomingMeshes = mutableListOf<MeshTemplate>()
            for (i in 0 until meshesArr.length()) {
                val meshObj = meshesArr.optJSONObject(i)
                if (meshObj != null) {
                    val id = meshObj.optString("id")
                    val name = meshObj.optString("name")
                    if (id.isNotEmpty()) {
                        incomingMeshes.add(MeshTemplate(id, name))
                    }
                }
            }
            val merged = mutableListOf<MeshTemplate>()
            val addedIds = mutableSetOf<String>()

            // Add/update from incoming
            for (incomingMesh in incomingMeshes) {
                merged.add(incomingMesh)
                addedIds.add(incomingMesh.id)
            }

            // Add current meshes that weren't in incoming (preserve local additions)
            for (currentMesh in currentMeshes) {
                if (!addedIds.contains(currentMesh.id)) {
                    merged.add(currentMesh)
                }
            }

            val filtered = merged.filterNot { it.id in tombstonedIds }
            if (filtered != currentMeshes) {
                setKnownMeshes(context, filtered)
                meshesChanged = true
            }
        } else if (tombstonedIds.isNotEmpty()) {
            // No incoming known_meshes this round, but a newly-merged tombstone may still apply
            // to what's stored locally (e.g. a delete-only sync payload).
            val filtered = currentMeshes.filterNot { it.id in tombstonedIds }
            if (filtered != currentMeshes) {
                setKnownMeshes(context, filtered)
                meshesChanged = true
            }
        }

        editor.putLong(KEY_CONFIG_VERSION, incomingVersion)
        editor.apply()

        val result = ConfigImportResult(
            applied = true,
            oldVersion = currentVersion,
            newVersion = incomingVersion,
            portChanged = portChanged,
            passwordChanged = passwordChanged,
            seedsChanged = seedsChanged,
            libraryChanged = libraryChanged,
            meshesChanged = meshesChanged
        )

        notifyListeners(result)
        return result
    }

    fun generateToken(context: Context, ttlSeconds: Long = 7 * 86400): String {
        val expiry = System.currentTimeMillis() + (ttlSeconds * 1000L)
        val secret = getAuthSecret(context)
        val signature = hmacSha256(secret, expiry.toString())
        return "$expiry.$signature"
    }

    fun validateToken(context: Context, token: String?): Boolean {
        if (!isPasswordSet(context)) return true
        if (token.isNullOrBlank()) return false

        val parts = token.split(".")
        if (parts.size != 2) return false

        val expiry = parts[0].toLongOrNull() ?: return false
        if (System.currentTimeMillis() > expiry) return false

        val secret = getAuthSecret(context)
        val expectedSig = hmacSha256(secret, parts[0])
        return MessageDigest.isEqual(expectedSig.toByteArray(Charsets.UTF_8), parts[1].toByteArray(Charsets.UTF_8))
    }

    private fun getAuthSecret(context: Context): ByteArray {
        val p = prefs(context)
        var secretHex = p.getString(KEY_AUTH_SECRET, null)
        if (secretHex.isNullOrBlank()) {
            val secret = ByteArray(32)
            SecureRandom().nextBytes(secret)
            secretHex = bytesToHex(secret)
            p.edit().putString(KEY_AUTH_SECRET, secretHex).apply()
            return secret
        }
        return hexToBytes(secretHex)
    }

    // Superseded by hashPasswordPbkdf2 (SET-BEHAVE-006) -- kept only to verify (and migrate)
    // hashes written by the old mechanism, either locally-persisted before the upgrade or
    // synced in from a peer still on the old build.
    private fun hashPasswordLegacySha256(password: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(password.toByteArray(Charsets.UTF_8))
    }

    private fun hashPasswordPbkdf2(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun encodePbkdf2Hash(iterations: Int, hash: ByteArray): String =
        "$PBKDF2_HASH_PREFIX$iterations$${bytesToHex(hash)}"

    private fun hmacSha256(key: ByteArray, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytesToHex(raw)
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in 0 until hex.length step 2) {
            result[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

