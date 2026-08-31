package com.cfox.droidmesh.settings

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Persisted user-facing configuration for DroidMesh / KSU.
 * Manages auto-update toggle, web admin password, and authentication tokens.
 */
object SettingsStore {
    private const val PREFS_NAME = "kiosk_satellite_updater_settings"
    private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
    private const val KEY_WEB_PASSWORD_HASH = "web_password_hash"
    private const val KEY_WEB_PASSWORD_SALT = "web_password_salt"
    private const val KEY_AUTH_SECRET = "auth_secret"
    private const val KEY_LOCAL_MESH_ID = "local_mesh_id"
    private const val KEY_LOCAL_MESH_NAME = "local_mesh_name"
    private const val KEY_CROSS_VLAN_SEEDS = "cross_vlan_seeds"

    private const val DEFAULT_AUTO_UPDATE_ENABLED = true

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
        prefs(context).edit().putStringSet(KEY_CROSS_VLAN_SEEDS, seeds).apply()
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
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply()
    }

    fun isPasswordSet(context: Context): Boolean =
        !prefs(context).getString(KEY_WEB_PASSWORD_HASH, null).isNullOrBlank()

    fun setPassword(context: Context, password: String): Boolean {
        val editor = prefs(context).edit()
        if (password.isBlank()) {
            editor.remove(KEY_WEB_PASSWORD_HASH)
            editor.remove(KEY_WEB_PASSWORD_SALT)
            editor.apply()
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
        prefs(context).edit()
            .remove(KEY_WEB_PASSWORD_HASH)
            .remove(KEY_WEB_PASSWORD_SALT)
            .apply()
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

