package com.cfox.droidmesh.service

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [PROGRAMMATIC] INST-TEST-015 / INST-TEST-016: gitea#40 — AutoInstallService's generic
 * text-pattern/resource-ID auto-click fallback must not fire inside a runtime permission-grant
 * dialog just because it happens to share a host package (`permissioncontroller`) and overlapping
 * button text ("Allow", "Continue", "OK") with the real install-confirmation dialog.
 *
 * Builds fake node trees via Mockito stand-ins for [AccessibilityNodeInfo] (a final Android
 * framework class — Mockito 5's default inline mock maker allows this without extra plugin
 * config) that respond to [AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId] and
 * [AccessibilityNodeInfo.findAccessibilityNodeInfosByText] exactly like
 * [AutoInstallService.isEligibleForGenericAutoClick] queries them.
 */
class AutoInstallServiceTest {

    private fun fakeNode(): AccessibilityNodeInfo = mock()

    /** A root node whose findBy* calls return empty for everything except the given stubs. */
    private fun buildRoot(
        viewIdHits: Map<String, List<AccessibilityNodeInfo>> = emptyMap(),
        textHits: Map<String, List<AccessibilityNodeInfo>> = emptyMap()
    ): AccessibilityNodeInfo {
        val root: AccessibilityNodeInfo = mock()
        whenever(root.findAccessibilityNodeInfosByViewId(org.mockito.kotlin.any())).thenReturn(emptyList())
        whenever(root.findAccessibilityNodeInfosByText(org.mockito.kotlin.any())).thenReturn(emptyList())
        for ((id, nodes) in viewIdHits) {
            whenever(root.findAccessibilityNodeInfosByViewId(id)).thenReturn(nodes)
        }
        for ((text, nodes) in textHits) {
            whenever(root.findAccessibilityNodeInfosByText(text)).thenReturn(nodes)
        }
        return root
    }

    // [PROGRAMMATIC] INST-TEST-015: genuine install-confirmation dialog stays eligible
    @Test
    fun testInstallConfirmationDialogFromPackageInstallerIsAlwaysEligible() {
        // com.android.packageinstaller has no other UI surface -- always eligible regardless of
        // node-tree contents.
        val root = buildRoot()
        assertTrue(
            AutoInstallService.isEligibleForGenericAutoClick("com.android.packageinstaller", root)
        )
    }

    @Test
    fun testInstallConfirmationDialogFromAmbiguousHostWithPositiveSignalIsEligible() {
        // permissioncontroller hosting the "Install unknown apps" / package install confirmation
        // flow -- carries an install-context text signal, no permission-grant signature.
        val doneNode = fakeNode()
        val root = buildRoot(
            textHits = mapOf("do you want to install" to listOf(doneNode))
        )
        assertTrue(
            AutoInstallService.isEligibleForGenericAutoClick(
                "com.android.permissioncontroller",
                root
            )
        )
    }

    // [PROGRAMMATIC] INST-TEST-016 (negative): generic runtime permission-grant dialog from the
    // same host package, with overlapping button text ("Allow"/"OK"), must NOT be eligible.
    @Test
    fun testRuntimePermissionGrantDialogFromSameHostPackageIsNotEligible() {
        val allowButton = fakeNode()
        val root = buildRoot(
            viewIdHits = mapOf(
                "com.android.permissioncontroller:id/permission_allow_button" to listOf(allowButton)
            ),
            textHits = mapOf(
                "to access your" to listOf(allowButton)
            )
        )
        assertFalse(
            AutoInstallService.isEligibleForGenericAutoClick(
                "com.android.permissioncontroller",
                root
            )
        )
    }

    @Test
    fun testRuntimePermissionGrantDialogWithNoInstallSignalIsNotEligible() {
        // No install-context text markers at all, from an ambiguous host package -- must not be
        // treated as eligible by default (deny-by-default for ambiguous hosts).
        val root = buildRoot()
        assertFalse(
            AutoInstallService.isEligibleForGenericAutoClick(
                "com.android.permissioncontroller",
                root
            )
        )
    }

    // Falsification guard: without this test, dropping hasPermissionGrantSignature() entirely
    // (keeping only the install-context positive check) would still pass every other test in
    // this file, because none of them stub BOTH signals on the same tree. A permission-grant
    // dialog racing with install-confirmation-looking text still in the node tree (the exact
    // scenario gitea#40 describes) must be vetoed regardless of the install-context signal.
    @Test
    fun testPermissionGrantSignatureVetoesEvenWhenInstallContextTextAlsoPresent() {
        val allowButton = fakeNode()
        val root = buildRoot(
            viewIdHits = mapOf(
                "com.android.permissioncontroller:id/permission_allow_button" to listOf(allowButton)
            ),
            textHits = mapOf(
                "installed." to listOf(fakeNode())
            )
        )
        assertFalse(
            AutoInstallService.isEligibleForGenericAutoClick(
                "com.android.permissioncontroller",
                root
            )
        )
    }

    // Falsification guard (independent reviewer finding): without this test, deleting
    // hasPermissionGrantSignature's text-marker fallback (matchesAnyText(root,
    // PERMISSION_GRANT_TEXT_MARKERS)) and detecting permission-grant dialogs only by the AOSP
    // resource-id list would still pass every other test, since they all stub a matching view-ID
    // alongside the text marker. An OEM-skinned or future-Android permission-grant dialog that
    // doesn't expose AOSP's exact `permission_*` resource ids, but still shows the standard grant
    // phrasing, must still be vetoed via the text-marker signal alone.
    @Test
    fun testPermissionGrantDetectedByTextMarkerAloneWithoutKnownResourceId() {
        val root = buildRoot(
            textHits = mapOf("while using the app" to listOf(fakeNode()))
        )
        assertFalse(
            AutoInstallService.isEligibleForGenericAutoClick(
                "com.android.permissioncontroller",
                root
            )
        )
    }

    // Falsification guard (independent reviewer finding): a malicious app could name itself
    // something that merely contains "packageinstaller" as a substring (e.g.
    // "com.evil.fakepackageinstallerhelper") to try to hit the old substring-match bypass and get
    // its own windows auto-clicked unconditionally. Only an exact match against the two known
    // AOSP/GSF installer package names is trusted unconditionally; anything else -- even a
    // substring match -- falls through to the ambiguous-host permission-grant/install-context
    // gating (and is denied here since neither signal is stubbed).
    @Test
    fun testSpoofedPackageNameContainingPackageinstallerSubstringIsNotAutomaticallyTrusted() {
        val root = buildRoot()
        assertFalse(
            AutoInstallService.isEligibleForGenericAutoClick(
                "com.evil.fakepackageinstallerhelper",
                root
            )
        )
    }

    @Test
    fun testSettingsHostRequiresInstallContextSignal() {
        val root = buildRoot()
        assertFalse(
            AutoInstallService.isEligibleForGenericAutoClick("com.android.settings", root)
        )

        val switchNode = fakeNode()
        val rootWithSignal = buildRoot(
            textHits = mapOf("allow from this source" to listOf(switchNode))
        )
        assertTrue(
            AutoInstallService.isEligibleForGenericAutoClick("com.android.settings", rootWithSignal)
        )
    }
}
