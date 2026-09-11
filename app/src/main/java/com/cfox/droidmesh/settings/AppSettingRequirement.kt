package com.cfox.droidmesh.settings

import org.json.JSONArray
import org.json.JSONObject

// ASET-BEHAVE-001: the typed catalog of OS settings an App Library entry can require. This is the
// only way a requirement is ever constructed from input (REST body, stored JSON, fleet sync), and
// every value that survives it is safe to interpolate into a loopback ADB command -- validation is
// whitelist-by-charset, never escaping. AdbLoopbackInstaller.isAllowedShellCommand
// (INST-BEHAVE-015) re-validates the assembled command independently, so this is the first of two
// gates, not the only one. See project/docs/SPEC/app-settings.md.
data class AppSettingRequirement(val type: SettingType, val value: String = "") {

    enum class SettingType(val key: String, val label: String) {
        ACCESSIBILITY_SERVICE("accessibility_service", "Accessibility service"),
        NOTIFICATION_LISTENER("notification_listener", "Notification access"),
        BATTERY_OPTIMIZATION("battery_optimization", "Battery optimization exemption"),
        APP_OP("app_op", "Special app access"),
        DEFAULT_HOME("default_home", "Default launcher"),
        RUNTIME_PERMISSION("runtime_permission", "Permission");

        /** True when this type's value names a `package/Class` component. */
        val isComponent: Boolean
            get() = this == ACCESSIBILITY_SERVICE || this == NOTIFICATION_LISTENER || this == DEFAULT_HOME

        companion object {
            fun fromKey(key: String?): SettingType? = values().firstOrNull { it.key == key }
        }
    }

    /** Stable identity for the UI and for repair reporting. */
    val id: String get() = if (value.isEmpty()) type.key else "${type.key}:$value"

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.key)
        put("value", value)
    }

    companion object {
        // The four special app accesses DroidMesh knows how to read and set. Deliberately a closed
        // set: `appops set <pkg> <OP> allow` with an arbitrary OP is a far wider capability than
        // this feature needs.
        val ALLOWED_APP_OPS: Set<String> = setOf(
            "SYSTEM_ALERT_WINDOW", "GET_USAGE_STATS", "WRITE_SETTINGS", "REQUEST_INSTALL_PACKAGES"
        )

        // Package names, class names and permission names, and nothing else. No `/`, no `:` (which
        // would splice a second component into the colon-joined enabled_accessibility_services
        // value), no whitespace, and no shell metacharacter of any kind.
        private val IDENTIFIER_REGEX = Regex("^[A-Za-z0-9_.]+$")

        fun isValidPackageName(packageName: String?): Boolean =
            packageName != null && IDENTIFIER_REGEX.matches(packageName)

        /**
         * Normalizes `pkg/.Cls` to `pkg/pkg.Cls` and rejects anything that isn't a single
         * well-formed component owned by [packageName]. Cross-package components are rejected
         * outright: a requirement on app X must never be able to enable a service belonging to
         * app Y, or a synced library entry becomes a way to turn on arbitrary components.
         */
        fun normalizeComponent(packageName: String, raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val slash = trimmed.indexOf('/')
            // Exactly one separator, with something on both sides.
            if (slash <= 0 || slash != trimmed.lastIndexOf('/') || slash == trimmed.length - 1) return null
            val pkg = trimmed.substring(0, slash)
            val rawClass = trimmed.substring(slash + 1)
            val cls = if (rawClass.startsWith(".")) pkg + rawClass else rawClass
            if (!IDENTIFIER_REGEX.matches(pkg) || !IDENTIFIER_REGEX.matches(cls)) return null
            if (pkg != packageName) return null
            return "$pkg/$cls"
        }

        /** Expands a short-form component for comparison. Never used to build a command. */
        fun expandComponent(component: String): String {
            val trimmed = component.trim()
            val slash = trimmed.indexOf('/')
            if (slash <= 0) return trimmed
            val pkg = trimmed.substring(0, slash)
            val cls = trimmed.substring(slash + 1)
            return if (cls.startsWith(".")) "$pkg/$pkg$cls" else "$pkg/$cls"
        }

        /** True when two component strings name the same component in either short or full form. */
        fun componentsEqual(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            return expandComponent(a).equals(expandComponent(b), ignoreCase = true)
        }

        fun create(packageName: String, type: SettingType, rawValue: String?): AppSettingRequirement? {
            if (!isValidPackageName(packageName)) return null
            return when (type) {
                // Carries no value at all -- the package name is the whole requirement.
                SettingType.BATTERY_OPTIMIZATION -> AppSettingRequirement(type, "")
                SettingType.ACCESSIBILITY_SERVICE,
                SettingType.NOTIFICATION_LISTENER,
                SettingType.DEFAULT_HOME -> {
                    val component = normalizeComponent(packageName, rawValue) ?: return null
                    AppSettingRequirement(type, component)
                }
                SettingType.APP_OP -> {
                    val op = rawValue?.trim().orEmpty()
                    if (op !in ALLOWED_APP_OPS) return null
                    AppSettingRequirement(type, op)
                }
                SettingType.RUNTIME_PERMISSION -> {
                    val permission = rawValue?.trim().orEmpty()
                    // Dotted identifier: `android.permission.X`, or a vendor-namespaced one.
                    if (!IDENTIFIER_REGEX.matches(permission) || !permission.contains('.')) return null
                    AppSettingRequirement(type, permission)
                }
            }
        }

        fun fromJson(packageName: String, json: JSONObject): AppSettingRequirement? {
            val type = SettingType.fromKey(json.optString("type")) ?: return null
            return create(packageName, type, json.optString("value"))
        }

        /** Drops invalid entries rather than failing the whole decode, and collapses duplicates. */
        fun parseList(packageName: String, array: JSONArray?): List<AppSettingRequirement> {
            if (array == null) return emptyList()
            val out = mutableListOf<AppSettingRequirement>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val req = fromJson(packageName, obj) ?: continue
                out.add(req)
            }
            return out.distinct()
        }
    }
}
