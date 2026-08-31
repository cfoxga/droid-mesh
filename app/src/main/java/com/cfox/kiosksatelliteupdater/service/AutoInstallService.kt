package com.cfox.kiosksatelliteupdater.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cfox.kiosksatelliteupdater.installer.AppVersionHelper
import com.cfox.kiosksatelliteupdater.utils.Logger

class AutoInstallService : AccessibilityService() {

    companion object {
        @Volatile
        var isServiceRunning: Boolean = false
            private set

        private val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.settings",
            "com.android.systemui"
        )

        private val INSTALL_BUTTON_IDS = setOf(
            "com.android.packageinstaller:id/ok_button",
            "com.google.android.packageinstaller:id/ok_button",
            "com.android.packageinstaller:id/btn_allow",
            "com.google.android.packageinstaller:id/btn_allow",
            "com.android.packageinstaller:id/done_button",
            "com.google.android.packageinstaller:id/done_button",
            "android:id/button1"
        )

        private val INSTALL_TEXT_PATTERNS = listOf(
            "install",
            "update",
            "done",
            "open",
            "allow",
            "continue",
            "ok",
            "uninstall"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastClickedText: String? = null
    private var lastClickTimestamp: Long = 0L
    private val DEBOUNCE_MS = 800L
    private var completionHandled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Logger.i("AutoInstallService connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (!isInstallerPackage(packageName)) {
            return
        }

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val nodesToInspect = mutableListOf<AccessibilityNodeInfo>()
        event.source?.let { nodesToInspect.add(it) }
        rootInActiveWindow?.let { if (!nodesToInspect.contains(it)) nodesToInspect.add(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                for (window in windows) {
                    window.root?.let { if (!nodesToInspect.contains(it)) nodesToInspect.add(it) }
                }
            } catch (e: Exception) {
                // Ignore windows retrieval error
            }
        }

        for (root in nodesToInspect) {
            try {
                inspectAndProcessNodeTree(root, packageName)
            } catch (e: Exception) {
                Logger.e("Error inspecting node tree", e)
            }
        }
    }

    private fun isInstallerPackage(packageName: String): Boolean {
        return INSTALLER_PACKAGES.contains(packageName) ||
                packageName.contains("packageinstaller", ignoreCase = true) ||
                packageName.contains("permissioncontroller", ignoreCase = true)
    }

    private fun inspectAndProcessNodeTree(root: AccessibilityNodeInfo, packageName: String) {
        val now = System.currentTimeMillis()

        // 1. Check for "App installed" completion indicator
        val isFinished = checkForCompletion(root)
        if (isFinished && !completionHandled) {
            Logger.i("Detected package installation completion screen")
            completionHandled = true

            // Look for "Done" or "Open" button
            val doneOrOpenClicked = findAndClickActionNode(root, listOf("done", "open"))

            // Launch target kiosk app into foreground after a slight delay
            mainHandler.postDelayed({
                launchTargetKioskApp()
                completionHandled = false
            }, 1000)
            return
        }

        // 2. Look for action buttons: Install, Update, Allow, Continue
        if (now - lastClickTimestamp > DEBOUNCE_MS) {
            val clicked = findAndClickActionNode(root, INSTALL_TEXT_PATTERNS)
            if (clicked) {
                lastClickTimestamp = now
            }
        }
    }

    private fun checkForCompletion(root: AccessibilityNodeInfo): Boolean {
        val completionTexts = listOf("app installed", "installed", "ready")
        for (term in completionTexts) {
            val matchingNodes = root.findAccessibilityNodeInfosByText(term)
            if (!matchingNodes.isNullOrEmpty()) {
                for (node in matchingNodes) {
                    val txt = node.text?.toString()?.lowercase() ?: ""
                    if (txt.contains("app installed") || txt.contains("installed.")) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun isNegativeText(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("cancel") || lower.contains("don't") || lower.contains("deny") ||
                lower.contains("abort") || lower.contains("dismiss") || lower.contains("reject")
    }

    private fun findAndClickActionNode(root: AccessibilityNodeInfo, allowedTexts: List<String>): Boolean {
        // First try resource IDs
        for (resId in INSTALL_BUTTON_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(resId)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    if (isNegativeText(text) || isNegativeText(desc)) {
                        continue
                    }
                    if (performClickOnNodeOrParent(node, "ResID: $resId")) {
                        return true
                    }
                }
            }
        }

        // Second: Recursive text search
        return searchAndClickNodeRecursive(root, allowedTexts)
    }

    private fun searchAndClickNodeRecursive(
        node: AccessibilityNodeInfo?,
        allowedTexts: List<String>
    ): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val nodeTextLower = text.lowercase()
        val descLower = contentDesc.lowercase()

        if (isNegativeText(text) || isNegativeText(contentDesc)) {
            return false
        }

        for (target in allowedTexts) {
            if (nodeTextLower == target || descLower == target ||
                (nodeTextLower.startsWith(target) && nodeTextLower.length <= target.length + 5)
            ) {
                if (performClickOnNodeOrParent(node, "Text: '$text' / Desc: '$contentDesc'")) {
                    return true
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            if (searchAndClickNodeRecursive(child, allowedTexts)) {
                return true
            }
        }

        return false
    }

    private fun performClickOnNodeOrParent(targetNode: AccessibilityNodeInfo, debugContext: String): Boolean {
        var current: AccessibilityNodeInfo? = targetNode

        while (current != null) {
            val nodeText = current.text?.toString() ?: targetNode.text?.toString() ?: ""
            val nodeDesc = current.contentDescription?.toString() ?: targetNode.contentDescription?.toString() ?: ""
            if (isNegativeText(nodeText) || isNegativeText(nodeDesc)) {
                return false
            }

            if (current.isClickable && current.isEnabled) {
                val now = System.currentTimeMillis()

                if (nodeText == lastClickedText && (now - lastClickTimestamp) < DEBOUNCE_MS) {
                    return false
                }

                Logger.i("Auto-clicking installer node [$debugContext] (class: ${current.className})")
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    lastClickedText = nodeText
                    lastClickTimestamp = now
                    return true
                }
            }
            current = current.parent
        }

        return false
    }

    private fun launchTargetKioskApp() {
        val targetPkg = AppVersionHelper.TARGET_PACKAGE
        Logger.i("Bringing $targetPkg to foreground post-installation")
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(launchIntent)
            Logger.i("Launched $targetPkg successfully")
        } else {
            Logger.w("Could not find launch intent for target package $targetPkg")
        }
    }

    override fun onInterrupt() {
        Logger.w("AutoInstallService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Logger.i("AutoInstallService destroyed")
    }
}
