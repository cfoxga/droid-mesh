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
    private const val KEY_CROSS_VLAN_SEEDS = "cross_vlan_seeds"

    private const val DEFAULT_AUTO_UPDATE_ENABLED = true
    private const val DEFAULT_WEB_SERVER_ENABLED = true
    private const val DEFAULT_WEB_SERVER_PORT = 2325

    data class ConfigImportResult(
        val applied: Boolean,
        val oldVersion: Long,
        val newVersion: Long,
        val portChanged: Boolean = false,
        val webServerToggled: Boolean = false,
        val autoUpdateToggled: Boolean = false,
        val passwordChanged: Boolean = false,
        val seedsChanged: Boolean = false
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


    fun getDefaultMeshId(context: Context): String {
        val model = (android.os.Build.MODEL ?: "").lowercase()
        val manufacturer = (android.os.Build.MANUFACTURER ?: "").lowercase()
        val isTv = try {
            context.packageManager?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) == true
        } catch (_: Exception) {
            false
        }
        return when {
            isTv || model.contains("googletv") || model.contains("google tv") || model.contains("chromecast") || model.contains("onn") -> "googletv"
            model.contains("portal") || manufacturer.contains("facebook") || manufacturer.contains("meta") -> "meta-portals"
            else -> "meta-portals"
        }
    }

    fun getDefaultMeshName(context: Context): String {
        return when (getDefaultMeshId(context)) {
            "googletv" -> "Google TV"
            "meta-portals" -> "Meta Portals"
            else -> "Meta Portals"
        }
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

    fun getCrossVlanSeeds(context: Context): Set<String> {
        val seeds = prefs(context).getStringSet(KEY_CROSS_VLAN_SEEDS, emptySet()) ?: emptySet()
        return seeds.toSet()
    }

    fun setCrossVlanSeeds(context: Context, seeds: Set<String>) {
        val prev = getCrossVlanSeeds(context)
        if (prev == seeds) return
        val ver = maxOf(System.currentTimeMillis(), getConfigVersion(context) + 1L)
        val editor = prefs(context).edit()
        editor.putStringSet(KEY_CROSS_VLAN_SEEDS, seeds)
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

    fun addCrossVlanSeed(context: Context, seed: String): Boolean {
        val cleanSeed = seed.trim()
        if (cleanSeed.isBlank()) return false
        val current = getCrossVlanSeeds(context).toMutableSet()
        val added = current.add(cleanSeed)
        if (added) {
            setCrossVlanSeeds(context, current)
        }
        return added
    }

    fun removeCrossVlanSeed(context: Context, seed: String): Boolean {
        val cleanSeed = seed.trim()
        val current = getCrossVlanSeeds(context).toMutableSet()
        val removed = current.remove(cleanSeed)
        if (removed) {
            setCrossVlanSeeds(context, current)
        }
        return removed
    }

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
        val seedsArr = JSONArray()
        getCrossVlanSeeds(context).forEach { seedsArr.put(it) }
        put("cross_vlan_seeds", seedsArr)
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

        // Synchronize cross-VLAN seeds
        if (json.has("cross_vlan_seeds")) {
            val seedsArr = json.optJSONArray("cross_vlan_seeds")
            val newSeeds = mutableSetOf<String>()
            if (seedsArr != null) {
                for (i in 0 until seedsArr.length()) {
                    val s = seedsArr.optString(i, "").trim()
                    if (s.isNotBlank()) newSeeds.add(s)
                }
            }
            val currentSeeds = getCrossVlanSeeds(context)
            if (currentSeeds != newSeeds) {
                editor.putStringSet(KEY_CROSS_VLAN_SEEDS, newSeeds)
                seedsChanged = true
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
            seedsChanged = seedsChanged
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

