package com.cfox.droidmesh.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cfox.droidmesh.installer.GoogleTvUpdatePolicy
import com.cfox.droidmesh.utils.Logger

class AutoInstallService : AccessibilityService() {

    companion object {
        @Volatile
        var isServiceRunning: Boolean = false
            private set

        /**
         * The package name of the app currently being installed/updated via
         * PackageInstallerDispatcher.dispatchInstall(), set by the dispatching caller
         * immediately before invoking it. The accessibility event stream only ever reports the
         * installer UI's own package (e.g. com.android.packageinstaller), never the package
         * actually being installed, so this is the only reliable source for "what to relaunch"
         * once the completion screen is detected. Cleared after being consumed.
         */
        @Volatile
        var pendingInstallPackage: String? = null

        internal data class PendingPlayStoreInstall(
            val packageName: String,
            val appName: String,
            val createdAtMillis: Long,
            val expiresAtMillis: Long,
            var initialWindowConsumed: Boolean = false
        )

        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val PLAY_STORE_REQUEST_TIMEOUT_MS = 60_000L
        private const val PLAY_STORE_INITIAL_WINDOW_TIMEOUT_MS = 10_000L

        @Volatile
        internal var pendingPlayStoreInstall: PendingPlayStoreInstall? = null

        @Synchronized
        fun beginPlayStoreInstall(packageName: String, appName: String): Boolean {
            val now = System.currentTimeMillis()
            val existing = pendingPlayStoreInstall
            if (existing != null && existing.expiresAtMillis > now) return false
            pendingPlayStoreInstall = PendingPlayStoreInstall(
                packageName = packageName,
                appName = appName,
                createdAtMillis = now,
                expiresAtMillis = now + PLAY_STORE_REQUEST_TIMEOUT_MS
            )
            return true
        }

        @Synchronized
        fun clearPendingPlayStoreInstall(packageName: String? = null) {
            if (packageName == null || pendingPlayStoreInstall?.packageName == packageName) {
                pendingPlayStoreInstall = null
            }
        }

        /**
         * Claims the one Play Store window transition that may authorize a click.  Claim before
         * reading an event source: accessibility is allowed to omit that source, and a later
         * Store transition must never become a substitute target page for this request.
         */
        @Synchronized
        internal fun claimInitialPlayStoreWindow(
            eventType: Int,
            nowMillis: Long
        ): PendingPlayStoreInstall? {
            val pending = pendingPlayStoreInstall ?: return null
            if (pending.expiresAtMillis < nowMillis) {
                pendingPlayStoreInstall = null
                return null
            }
            if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || pending.initialWindowConsumed) {
                return null
            }
            pending.initialWindowConsumed = true
            return pending
        }

        internal fun isEligiblePlayStoreInstallWindow(
            eventType: Int,
            eventPackage: String,
            rootPackage: String,
            pending: PendingPlayStoreInstall?,
            visibleText: String,
            nowMillis: Long
        ): Boolean = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventPackage == PLAY_STORE_PACKAGE &&
            rootPackage == PLAY_STORE_PACKAGE &&
            pending != null &&
            nowMillis - pending.createdAtMillis <= PLAY_STORE_INITIAL_WINDOW_TIMEOUT_MS &&
            pending.expiresAtMillis >= nowMillis &&
            pending.appName.isNotBlank() &&
            visibleText.contains(pending.appName, ignoreCase = true)

        const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"
        private const val PROJECTIVY_RESTORE_TIMEOUT_MS = 15_000L

        internal data class PendingProjectivyRestore(
            val createdAtMillis: Long,
            val expiresAtMillis: Long
        )

        @Volatile
        internal var pendingProjectivyRestore: PendingProjectivyRestore? = null

        @Synchronized
        fun beginProjectivyRestore(): Boolean {
            val now = System.currentTimeMillis()
            pendingProjectivyRestore = PendingProjectivyRestore(
                createdAtMillis = now,
                expiresAtMillis = now + PROJECTIVY_RESTORE_TIMEOUT_MS
            )
            return true
        }

        @Synchronized
        fun clearPendingProjectivyRestore() {
            pendingProjectivyRestore = null
        }

        internal fun isEligibleProjectivyRestoreWindow(
            eventType: Int,
            eventPackage: String,
            pending: PendingProjectivyRestore?,
            nowMillis: Long
        ): Boolean = (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                     eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) &&
            eventPackage == PROJECTIVY_PACKAGE &&
            pending != null &&
            pending.expiresAtMillis >= nowMillis

        internal fun findProjectivyRestoreButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val targets = listOf("restore backup", "restore")
            for (target in targets) {
                val nodes = root.findAccessibilityNodeInfosByText(target) ?: emptyList()
                for (node in nodes) {
                    val text = node.text?.toString()?.trim()?.lowercase() ?: ""
                    if (text == target || text.contains(target)) {
                        var cur: AccessibilityNodeInfo? = node
                        while (cur != null) {
                            if (cur.isClickable) return cur
                            cur = cur.parent
                        }
                    }
                }
            }
            return null
        }

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

        /**
         * INST-BEHAVE-013 (gitea#40): packages that exclusively host the package-install/uninstall
         * confirmation UI. Generic resource-ID/text-pattern auto-click is always safe here because
         * these packages have no other UI surface DroidMesh's accessibility service could
         * mistakenly click through — unlike [AMBIGUOUS_HOST_PACKAGES] below.
         */
        private val INSTALL_ONLY_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
        )

        /**
         * INST-BEHAVE-013: these packages also host runtime permission-grant dialogs
         * (`GrantPermissionsActivity` et al.) and other unrelated system prompts that are
         * completely separate from the install-confirmation flow but share the same package name
         * — and often the same generic button text ("Allow", "Continue", "OK") — as the real
         * install dialog. See [isEligibleForGenericAutoClick].
         */
        private val AMBIGUOUS_HOST_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.settings",
            "com.android.systemui"
        )

        /**
         * AOSP `GrantPermissionsActivity` resource-id signature. Presence of any of these nodes
         * means the window currently in front of the accessibility service is a runtime
         * permission-grant prompt, not the install-confirmation dialog, regardless of host
         * package — and must never be auto-clicked by the generic fallback.
         */
        private val PERMISSION_GRANT_VIEW_IDS = listOf(
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.android.permissioncontroller:id/permission_allow_all_button",
            "com.android.permissioncontroller:id/permission_deny_button",
            "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button",
            "com.android.permissioncontroller:id/permission_message",
            "com.google.android.permissioncontroller:id/permission_allow_button",
            "com.google.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.google.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.google.android.permissioncontroller:id/permission_allow_all_button",
            "com.google.android.permissioncontroller:id/permission_deny_button",
            "com.google.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button",
            "com.google.android.permissioncontroller:id/permission_message"
        )

        /** Phrasing that only ever appears in a runtime permission-grant dialog's body text. */
        private val PERMISSION_GRANT_TEXT_MARKERS = listOf(
            "while using the app",
            "only this time",
            "to access this device",
            "to access your",
            "allow this app to",
            "don't allow"
        )

        /**
         * Positive signal that an [AMBIGUOUS_HOST_PACKAGES] window really is the
         * install/uninstall confirmation dialog (e.g. Settings' "Install unknown apps" source
         * toggle, or a permissioncontroller-hosted install confirmation on some OEM builds) rather
         * than a generic permission-grant, storage-access, or other system dialog that happens to
         * share button text with the installer.
         */
        private val INSTALL_CONTEXT_TEXT_MARKERS = listOf(
            "do you want to install",
            "install this app",
            "install this application",
            "unknown apps",
            "unknown sources",
            "allow from this source",
            "do you want to uninstall",
            "uninstall this app",
            "app installed",
            "installed."
        )

        /**
         * INST-BEHAVE-013 (gitea#40): whether [root] — a window belonging to [packageName],
         * already confirmed to be one of [INSTALLER_PACKAGES] by [isInstallerPackage] — is
         * eligible for the generic resource-ID/text-pattern auto-click pass.
         *
         * [INSTALL_ONLY_PACKAGES] are always eligible: that package has no other UI surface.
         * [AMBIGUOUS_HOST_PACKAGES] (and anything else that merely resembles a known installer
         * package name without exactly matching one) also host runtime permission-grant dialogs
         * unrelated to the install flow, so a window from one of those is only eligible when the
         * node tree shows a positive install-confirmation signal AND carries no permission-grant
         * signature — deny-by-default otherwise, including when neither signal is present.
         */
        internal fun isEligibleForGenericAutoClick(
            packageName: String,
            root: AccessibilityNodeInfo
        ): Boolean {
            // Exact match only -- unlike isInstallerPackage()'s deliberately broad substring
            // match (used only to decide whether to inspect a window at all), the "always
            // eligible, no further checks" bypass must not trust a merely-substring-matching
            // package name. A malicious app could otherwise name itself e.g.
            // "com.evil.fakepackageinstallerhelper" to get its own windows auto-clicked
            // unconditionally. Anything that isn't exactly one of the two known AOSP/GSF installer
            // packages falls through to the ambiguous-host gating below.
            if (INSTALL_ONLY_PACKAGES.any { packageName.equals(it, ignoreCase = true) }) {
                return true
            }

            if (hasPermissionGrantSignature(root)) {
                return false
            }
            return hasInstallContextSignature(root)
        }

        private fun hasPermissionGrantSignature(root: AccessibilityNodeInfo): Boolean {
            for (id in PERMISSION_GRANT_VIEW_IDS) {
                if (!root.findAccessibilityNodeInfosByViewId(id).isNullOrEmpty()) {
                    return true
                }
            }
            return matchesAnyText(root, PERMISSION_GRANT_TEXT_MARKERS)
        }

        private fun hasInstallContextSignature(root: AccessibilityNodeInfo): Boolean {
            return matchesAnyText(root, INSTALL_CONTEXT_TEXT_MARKERS)
        }

        private fun matchesAnyText(root: AccessibilityNodeInfo, markers: List<String>): Boolean {
            for (marker in markers) {
                if (!root.findAccessibilityNodeInfosByText(marker).isNullOrEmpty()) {
                    return true
                }
            }
            return false
        }

        /**
         * INST-BEHAVE-014 (gitea#56, incomplete-fix followup to gitea#40/INST-BEHAVE-013): the
         * first gate deciding whether a window is inspected at all. `accessibility_service_config.xml`
         * has no `android:packageNames` filter, so this exact-match check is the only thing standing
         * between "any installed app's windows" and the accessibility service's node-tree inspection.
         * Exact match only against the known installer/permission-controller package allowlist --
         * unlike the old `.contains("packageinstaller"/"permissioncontroller", ignoreCase = true)`
         * substring branches this replaces, which a spoofed package name like
         * "com.attacker.custompackageinstaller" passed. INST-BEHAVE-013's exact-match scoping only
         * protected the downstream, more specific INSTALL_ONLY_PACKAGES bypass check -- it never
         * covered this earlier, more fundamental gate.
         */
        internal fun isInstallerPackage(packageName: String): Boolean {
            return INSTALLER_PACKAGES.any { packageName.equals(it, ignoreCase = true) }
        }
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
        // The startup auto-action pass can beat Android's asynchronous accessibility bind. Wake
        // it again now that Store installs can safely be dispatched instead of waiting an hour.
        UpdaterForegroundService.wakeMeshAutoActionLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == PLAY_STORE_PACKAGE) {
            handlePlayStoreEvent(event)
            return
        }
        if (packageName == PROJECTIVY_PACKAGE) {
            handleProjectivyEvent(event)
            return
        }
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
                inspectAndProcessNodeTree(packageName, root)
            } catch (e: Exception) {
                Logger.e("Error inspecting node tree", e)
            }
        }
    }

    private fun handlePlayStoreEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        val pending = claimInitialPlayStoreWindow(event.eventType, now) ?: return
        // `event.source` belongs to the window that emitted this exact Play Store event. Do not
        // inspect rootInActiveWindow here: it can already belong to another foreground app by the
        // time this handler runs, which would turn a Play Store event into an arbitrary-app click.
        val root = event.source ?: run {
            clearPendingPlayStoreInstall(pending.packageName)
            Logger.w("Play Store auto-install target page has no accessible root for ${pending.packageName}")
            return
        }
        val rootPackage = root.packageName?.toString() ?: run {
            clearPendingPlayStoreInstall(pending.packageName)
            Logger.w("Play Store auto-install target page has no package for ${pending.packageName}")
            return
        }
        val visibleLabels = collectVisibleLabels(root)
        if (!isEligiblePlayStoreInstallWindow(event.eventType, PLAY_STORE_PACKAGE, rootPackage, pending, visibleLabels.joinToString("\n"), now)) {
            // Only the initial Play Store window transition is trusted. Do not keep a pending
            // request alive into a later search, collection, or user-navigation screen that could
            // happen to mention the same app title.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                clearPendingPlayStoreInstall(pending.packageName)
                Logger.w("Play Store auto-install target page did not appear for ${pending.packageName}")
            }
            return
        }
        // A detail page has one exact Install node. Search/recommendation surfaces can contain
        // several; skip those rather than guessing which app an Install button belongs to.
        val installNode = findExactTextNodes(root, "install").singleOrNull() ?: run {
            clearPendingPlayStoreInstall(pending.packageName)
            Logger.w("Play Store auto-install page for ${pending.packageName} has no unique Install action")
            return
        }
        // Play Store resource IDs are obfuscated on Google TV. Do not reuse the generic installer
        // resource-ID fallback here: this path has proved a single explicit Install label on the
        // exact Play Store event root for the pending title.
        if (performClickOnNodeOrParent(installNode, "Play Store Install")) {
            clearPendingPlayStoreInstall(pending.packageName)
            Logger.i("Clicked Play Store Install for ${pending.packageName}")
        }
    }

    private fun handleProjectivyEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        val pending = pendingProjectivyRestore ?: return
        if (!isEligibleProjectivyRestoreWindow(event.eventType, event.packageName?.toString() ?: "", pending, now)) {
            return
        }
        val root = rootInActiveWindow ?: event.source ?: return
        val restoreButton = findProjectivyRestoreButton(root)
        if (restoreButton != null) {
            Logger.i("AutoInstallService: clicking Projectivy restore button")
            if (performClickOnNodeOrParent(restoreButton, "Projectivy Restore")) {
                clearPendingProjectivyRestore()
            }
        }
    }


    private fun collectVisibleLabels(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        return buildList {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
            for (i in 0 until node.childCount) addAll(collectVisibleLabels(node.getChild(i)))
        }
    }

    private fun findExactTextNodes(node: AccessibilityNodeInfo?, target: String): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return buildList {
            val text = node.text?.toString()?.trim().orEmpty()
            val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.equals(target, ignoreCase = true) || contentDesc.equals(target, ignoreCase = true)) add(node)
            for (i in 0 until node.childCount) addAll(findExactTextNodes(node.getChild(i), target))
        }
    }

    private fun inspectAndProcessNodeTree(packageName: String, root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()

        // 1. Check for "App installed" completion indicator
        val isFinished = checkForCompletion(root)
        if (isFinished && !completionHandled) {
            Logger.i("Detected package installation completion screen")
            completionHandled = true

            // Look for "Done" or "Open" button -- gated by isEligibleForGenericAutoClick just
            // like the generic pass below (INST-BEHAVE-013 / gitea#40): an ambiguous host
            // package's unrelated permission-grant dialog that happens to also carry
            // "installed."-like text must not have its Allow/Continue/OK button clicked here
            // either. Legitimate completion screens on packageinstaller are unaffected (always
            // eligible); legitimate completion screens on an ambiguous host still pass because
            // checkForCompletion's "app installed"/"installed." match is itself one of
            // INSTALL_CONTEXT_TEXT_MARKERS.
            if (isEligibleForGenericAutoClick(packageName, root)) {
                findAndClickActionNode(root, listOf("done", "open"))
            }

            // Launch the just-installed app into foreground after a slight delay. `packageName`
            // here is the installer UI's own package (e.g. com.android.packageinstaller), never
            // the app that was actually installed — use the package the dispatching caller
            // recorded instead.
            val installedPkg = pendingInstallPackage
            pendingInstallPackage = null
            mainHandler.postDelayed({
                if (installedPkg != null) {
                    launchUpdatedApp(installedPkg)
                } else {
                    Logger.w("Install completion detected but no pendingInstallPackage was recorded — not relaunching")
                }
                completionHandled = false
            }, 1000)
            return
        }

        // 2. Look for action buttons: Install, Update, Allow, Continue -- gated by
        // isEligibleForGenericAutoClick so an ambiguous host package (permissioncontroller,
        // settings, systemui) can't have this generic pass fire inside an unrelated runtime
        // permission-grant dialog (INST-BEHAVE-013 / gitea#40).
        if (now - lastClickTimestamp > DEBOUNCE_MS && isEligibleForGenericAutoClick(packageName, root)) {
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

    private fun launchUpdatedApp(targetPkg: String) {
        if (GoogleTvUpdatePolicy.suppressPostInstallLaunch(this)) {
            Logger.i("Google TV install completed for $targetPkg without foreground launch")
            return
        }
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
        clearPendingPlayStoreInstall()
        Logger.w("AutoInstallService interrupted")
    }

    override fun onDestroy() {
        clearPendingPlayStoreInstall()
        super.onDestroy()
        isServiceRunning = false
        Logger.i("AutoInstallService destroyed")
    }
}
