package com.cfox.droidmesh.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Persisted user-facing configuration for DroidMesh / KSU.
 * Manages auto-update toggle, web admin password, web server settings,
 * mesh parameters, and mesh-wide configuration synchronization.
 */
object SettingsStore {
    private const val PREFS_NAME = "kiosk_satellite_updater_settings"
    private const val KEY_CONFIG_VERSION = "config_version"
    private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
    private const val KEY_WEB_SERVER_ENABLED = "web_server_enabled"
    private const val KEY_WEB_SERVER_PORT = "web_server_port"
    private const val KEY_WEB_PASSWORD_HASH = "web_password_hash"
    private const val KEY_WEB_PASSWORD_SALT = "web_password_salt"
    private const val KEY_AUTH_SECRET = "auth_secret"
    private const val KEY_LOCAL_MESH_ID = "local_mesh_id"
    private const val KEY_LOCAL_MESH_NAME = "local_mesh_name"
    private const val KEY_PERSISTENT_CONNECTIONS = "persistent_connections"
    private const val KEY_CUSTOM_DEVICE_NAME = "custom_device_name"
    private const val KEY_MESH_APP_LIBRARIES = "mesh_app_libraries"

    private const val DEFAULT_AUTO_UPDATE_ENABLED = true
    private const val DEFAULT_WEB_SERVER_ENABLED = true
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
                return MeshAppConfig(
                    packageName = pkg,
                    appName = if (name.isNotBlank()) name else pkg,
                    managed = json.optBoolean("managed", false),
                    autoInstall = json.optBoolean("autoInstall", false),
                    targetVersion = json.optString("targetVersion", "latest"),
                    autoUpdate = json.optBoolean("autoUpdate", false),
                    isSideloaded = isSideload,
                    downloadUrl = json.optString("downloadUrl", "")
                )
            }
        }
    }

    data class ConfigImportResult(
        val applied: Boolean,
        val oldVersion: Long,
        val newVersion: Long,
        val portChanged: Boolean = false,
        val webServerToggled: Boolean = false,
        val autoUpdateToggled: Boolean = false,
        val passwordChanged: Boolean = false,
        val seedsChanged: Boolean = false,
        val libraryChanged: Boolean = false
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

    fun isWebServerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEB_SERVER_ENABLED, DEFAULT_WEB_SERVER_ENABLED)

    fun setWebServerEnabled(context: Context, enabled: Boolean) {
        val prev = isWebServerEnabled(context)
        if (prev == enabled) return
        val editor = prefs(context).edit()
        val ver = maxOf(System.currentTimeMillis(), getConfigVersion(context) + 1L)
        editor.putBoolean(KEY_WEB_SERVER_ENABLED, enabled)
        editor.putLong(KEY_CONFIG_VERSION, ver)
        editor.apply()
        notifyListeners(
            ConfigImportResult(
                applied = true,
                oldVersion = ver - 1,
                newVersion = ver,
                webServerToggled = true
            )
        )
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
        return added
    }

    fun removePersistentConnection(context: Context, connection: String): Boolean {
        val cleanConnection = connection.trim()
        val current = getPersistentConnections(context).toMutableSet()
        val removed = current.remove(cleanConnection)
        if (removed) {
            setPersistentConnections(context, current)
        }
        return removed
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

    fun isAutoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPDATE_ENABLED, DEFAULT_AUTO_UPDATE_ENABLED)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        val prev = isAutoUpdateEnabled(context)
        if (prev == enabled) return
        val ver = maxOf(System.currentTimeMillis(), getConfigVersion(context) + 1L)
        val editor = prefs(context).edit()
        editor.putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled)
        editor.putLong(KEY_CONFIG_VERSION, ver)
        editor.apply()
        notifyListeners(
            ConfigImportResult(
                applied = true,
                oldVersion = ver - 1,
                newVersion = ver,
                autoUpdateToggled = true
            )
        )
    }

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
        val hash = hashPassword(password, salt)

        editor.putString(KEY_WEB_PASSWORD_SALT, bytesToHex(salt))
        editor.putString(KEY_WEB_PASSWORD_HASH, bytesToHex(hash))
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
        val expectedHash = hexToBytes(hashHex)
        val actualHash = hashPassword(password, salt)

        return MessageDigest.isEqual(expectedHash, actualHash)
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

        // Default base managed apps if not explicitly configured (only for Meta Portals mesh partition)
        if (meshId == "meta-portals" || meshId == "default") {
            val defaultSatellite = MeshAppConfig(
                packageName = com.cfox.droidmesh.installer.AppVersionHelper.TARGET_PACKAGE,
                appName = "Kiosk Satellite",
                managed = true,
                autoInstall = true,
                targetVersion = "latest",
                autoUpdate = true,
                isSideloaded = true
            )
            result[defaultSatellite.packageName] = defaultSatellite
        }

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
        result.remove("com.cfox.kiosksatelliteupdater")
        result.remove(context.packageName)
        return result
    }

    fun setMeshAppConfig(context: Context, meshId: String, appConfig: MeshAppConfig): Long {
        val root = getAllMeshAppLibraries(context)
        val meshObj = root.optJSONObject(meshId) ?: JSONObject()
        meshObj.put(appConfig.packageName, appConfig.toJson())
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

    fun exportConfigJson(context: Context): JSONObject = JSONObject().apply {
        put("config_version", getConfigVersion(context))
        put("web_server_enabled", isWebServerEnabled(context))
        put("web_server_port", getWebServerPort(context))
        val salt = prefs(context).getString(KEY_WEB_PASSWORD_SALT, null)
        val hash = prefs(context).getString(KEY_WEB_PASSWORD_HASH, null)
        val secret = prefs(context).getString(KEY_AUTH_SECRET, null)
        put("web_password_salt", salt ?: JSONObject.NULL)
        put("web_password_hash", hash ?: JSONObject.NULL)
        put("auth_secret", secret ?: JSONObject.NULL)
        put("auto_update_enabled", isAutoUpdateEnabled(context))
        val connectionsArr = JSONArray()
        getPersistentConnections(context).forEach { connectionsArr.put(it) }
        put("persistent_connections", connectionsArr)
        // For backward compatibility, also include under old key
        put("cross_vlan_seeds", connectionsArr)
        put("mesh_app_libraries", getAllMeshAppLibraries(context))
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
        var webServerToggled = false
        var autoUpdateToggled = false
        var passwordChanged = false
        var seedsChanged = false
        var libraryChanged = false

        if (json.has("web_server_enabled")) {
            val enabled = json.getBoolean("web_server_enabled")
            if (isWebServerEnabled(context) != enabled) {
                editor.putBoolean(KEY_WEB_SERVER_ENABLED, enabled)
                webServerToggled = true
            }
        }

        if (json.has("web_server_port")) {
            val port = json.getInt("web_server_port")
            if (port in 1024..65535 && getWebServerPort(context) != port) {
                editor.putInt(KEY_WEB_SERVER_PORT, port)
                portChanged = true
            }
        }

        if (json.has("auto_update_enabled")) {
            val autoUpdate = json.getBoolean("auto_update_enabled")
            if (isAutoUpdateEnabled(context) != autoUpdate) {
                editor.putBoolean(KEY_AUTO_UPDATE_ENABLED, autoUpdate)
                autoUpdateToggled = true
            }
        }

        // Synchronize password hash, salt, and auth secret
        if (json.has("web_password_hash")) {
            val hash = if (json.isNull("web_password_hash")) null else json.optString("web_password_hash")
            val salt = if (json.isNull("web_password_salt")) null else json.optString("web_password_salt")
            val secret = if (json.isNull("auth_secret")) null else json.optString("auth_secret")

            val currentHash = prefs(context).getString(KEY_WEB_PASSWORD_HASH, null)
            val currentSalt = prefs(context).getString(KEY_WEB_PASSWORD_SALT, null)
            val currentSecret = prefs(context).getString(KEY_AUTH_SECRET, null)

            if (hash != currentHash || salt != currentSalt || secret != currentSecret) {
                if (hash.isNullOrBlank()) {
                    editor.remove(KEY_WEB_PASSWORD_HASH)
                    editor.remove(KEY_WEB_PASSWORD_SALT)
                } else {
                    editor.putString(KEY_WEB_PASSWORD_HASH, hash)
                    if (!salt.isNullOrBlank()) editor.putString(KEY_WEB_PASSWORD_SALT, salt)
                    if (!secret.isNullOrBlank()) editor.putString(KEY_AUTH_SECRET, secret)
                }
                passwordChanged = true
            }
        }

        // Synchronize persistent connections (handles both old "cross_vlan_seeds" and new "persistent_connections" keys)
        var connectionsArr = json.optJSONArray("persistent_connections")
        if (connectionsArr == null) {
            connectionsArr = json.optJSONArray("cross_vlan_seeds")
        }
        if (connectionsArr != null) {
            val newConnections = mutableSetOf<String>()
            for (i in 0 until connectionsArr.length()) {
                val s = connectionsArr.optString(i, "").trim()
                if (s.isNotBlank()) newConnections.add(s)
            }
            val currentConnections = getPersistentConnections(context)
            if (currentConnections != newConnections) {
                editor.putStringSet(KEY_PERSISTENT_CONNECTIONS, newConnections)
                seedsChanged = true
            }
        }

        // Synchronize mesh app libraries
        if (json.has("mesh_app_libraries")) {
            val librariesObj = json.optJSONObject("mesh_app_libraries")
            if (librariesObj != null) {
                val currentLibraries = getAllMeshAppLibraries(context).toString()
                val incomingLibraries = librariesObj.toString()
                if (currentLibraries != incomingLibraries) {
                    editor.putString(KEY_MESH_APP_LIBRARIES, incomingLibraries)
                    libraryChanged = true
                }
            }
        }

        editor.putLong(KEY_CONFIG_VERSION, incomingVersion)
        editor.apply()

        val result = ConfigImportResult(
            applied = true,
            oldVersion = currentVersion,
            newVersion = incomingVersion,
            portChanged = portChanged,
            webServerToggled = webServerToggled,
            autoUpdateToggled = autoUpdateToggled,
            passwordChanged = passwordChanged,
            seedsChanged = seedsChanged,
            libraryChanged = libraryChanged
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

    private fun hashPassword(password: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(password.toByteArray(Charsets.UTF_8))
    }

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

