package com.cfox.droidmesh.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.cfox.droidmesh.utils.Logger

/**
 * INST-BEHAVE-023: registers DroidMesh as an Android Device Admin so it can be promoted to
 * Device Owner via `dpm set-device-owner` during provisioning. Declares no policies beyond the
 * bare minimum the OS requires to accept the admin (see res/xml/device_admin.xml) -- Device
 * Owner package installation (INST-BEHAVE-024) comes from the Device Owner role itself, not from
 * any DevicePolicyManager policy this receiver would enforce.
 */
class DroidMeshDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Logger.i("DroidMesh device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Logger.i("DroidMesh device admin disabled")
    }
}
