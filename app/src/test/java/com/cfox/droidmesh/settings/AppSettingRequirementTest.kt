package com.cfox.droidmesh.settings

import com.cfox.droidmesh.settings.AppSettingRequirement.SettingType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PROGRAMMATIC] ASET-TEST-001/002: the typed per-app settings catalog is the only thing that ever
 * becomes a loopback ADB command, so its validation is the security boundary for what a synced or
 * admin-supplied requirement can make a device do. See project/docs/SPEC/app-settings.md.
 */
class AppSettingRequirementTest {

    private val tqa = "dev.vodik7.tvquickactions.free"
    private val projectivy = "com.spocky.projengmenu"
    private val kiosk = "me.jxl.kiosk_satellite"

    // [PROGRAMMATIC] ASET-TEST-001
    @Test
    fun testCreateAcceptsEachTypeAndNormalizesComponents() {
        val a11y = AppSettingRequirement.create(
            tqa, SettingType.ACCESSIBILITY_SERVICE, "$tqa/dev.vodik7.tvquickactions.KeyAccessibilityService"
        )
        assertEquals("$tqa/dev.vodik7.tvquickactions.KeyAccessibilityService", a11y?.value)

        // Great Room stores Projectivy's service in short form.
        assertEquals(
            "$projectivy/com.spocky.projengmenu.services.ProjectivyAccessibilityService",
            AppSettingRequirement.create(
                projectivy, SettingType.ACCESSIBILITY_SERVICE, "$projectivy/.services.ProjectivyAccessibilityService"
            )?.value
        )
        assertEquals(
            "$projectivy/com.spocky.projengmenu.ui.home.MainActivity",
            AppSettingRequirement.create(projectivy, SettingType.DEFAULT_HOME, " $projectivy/.ui.home.MainActivity ")?.value
        )
        assertEquals(
            "$projectivy/com.spocky.projengmenu.services.NotificationListener",
            AppSettingRequirement.create(projectivy, SettingType.NOTIFICATION_LISTENER, "$projectivy/.services.NotificationListener")?.value
        )
        assertEquals(
            "battery requirement never carries a value",
            "",
            AppSettingRequirement.create(kiosk, SettingType.BATTERY_OPTIMIZATION, "anything")?.value
        )
        assertEquals("SYSTEM_ALERT_WINDOW", AppSettingRequirement.create(kiosk, SettingType.APP_OP, "SYSTEM_ALERT_WINDOW")?.value)
        assertEquals(
            "android.permission.ACCESS_FINE_LOCATION",
            AppSettingRequirement.create(tqa, SettingType.RUNTIME_PERMISSION, "android.permission.ACCESS_FINE_LOCATION")?.value
        )

        val json = a11y!!.toJson()
        assertEquals("accessibility_service", json.getString("type"))
        assertEquals(a11y, AppSettingRequirement.fromJson(tqa, json))
        assertEquals("accessibility_service:${a11y.value}", a11y.id)
        assertEquals("battery_optimization", AppSettingRequirement.create(kiosk, SettingType.BATTERY_OPTIMIZATION, null)?.id)
    }

    // [PROGRAMMATIC] ASET-TEST-002 (negative)
    @Test
    fun testCreateRejectsCrossPackageUnsafeAndUnknownValues() {
        assertNull("component owned by another package", AppSettingRequirement.create(kiosk, SettingType.ACCESSIBILITY_SERVICE, "com.evil.app/.Svc"))
        assertNull("home owned by another package", AppSettingRequirement.create(kiosk, SettingType.DEFAULT_HOME, "com.evil.app/.Home"))
        assertNull("listener owned by another package", AppSettingRequirement.create(kiosk, SettingType.NOTIFICATION_LISTENER, "com.evil.app/.L"))
        assertNull("semicolon", AppSettingRequirement.create(kiosk, SettingType.ACCESSIBILITY_SERVICE, "$kiosk/.Svc;reboot"))
        assertNull("space", AppSettingRequirement.create(kiosk, SettingType.RUNTIME_PERMISSION, "android.permission.CAMERA rm"))
        assertNull("dollar", AppSettingRequirement.create(kiosk, SettingType.ACCESSIBILITY_SERVICE, "$kiosk/.Svc\$Inner"))
        assertNull("backtick", AppSettingRequirement.create(kiosk, SettingType.RUNTIME_PERMISSION, "`reboot`"))
        assertNull(
            "colon would splice a second component into a colon-joined secure setting",
            AppSettingRequirement.create(kiosk, SettingType.ACCESSIBILITY_SERVICE, "$kiosk/.Svc:com.evil/.X")
        )
        assertNull("op outside catalog", AppSettingRequirement.create(kiosk, SettingType.APP_OP, "RUN_IN_BACKGROUND"))
        assertNull("op case must match catalog", AppSettingRequirement.create(kiosk, SettingType.APP_OP, "system_alert_window"))
        assertNull("blank permission", AppSettingRequirement.create(kiosk, SettingType.RUNTIME_PERMISSION, ""))
        assertNull("blank component", AppSettingRequirement.create(kiosk, SettingType.ACCESSIBILITY_SERVICE, null))
        assertNull("missing class", AppSettingRequirement.create(kiosk, SettingType.ACCESSIBILITY_SERVICE, "$kiosk/"))
        assertNull("invalid owning package", AppSettingRequirement.create("bad pkg;", SettingType.BATTERY_OPTIMIZATION, ""))
        assertNull("unknown type", AppSettingRequirement.fromJson(kiosk, JSONObject().put("type", "shell").put("value", "reboot")))

        val valid = JSONObject().put("type", "battery_optimization").put("value", "")
        val parsed = AppSettingRequirement.parseList(
            kiosk,
            JSONArray()
                .put(valid)
                .put(JSONObject(valid.toString()))
                .put(JSONObject().put("type", "app_op").put("value", "BOGUS"))
                .put(JSONObject().put("type", "accessibility_service").put("value", "com.evil.app/.Svc"))
                .put("not an object")
        )
        assertEquals(listOf(AppSettingRequirement(SettingType.BATTERY_OPTIMIZATION, "")), parsed)
        assertEquals(emptyList<AppSettingRequirement>(), AppSettingRequirement.parseList(kiosk, null))
    }
}
