package com.cfox.droidmesh.installer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [PROGRAMMATIC] INST-TEST-004/006/007: exercises AdbLoopbackInstaller's real wire protocol
 * (framing, AUTH round-trip, exec capture) against a fake in-process ADB daemon on a loopback
 * socket bound to an ephemeral port — no real device or adbd required. See
 * project/docs/SPEC/installer.md / provisioning.md.
 */
class AdbLoopbackInstallerTest {

    private object Cmd {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
    }

    private data class Header(val command: Int, val arg0: Int, val arg1: Int, val dataLength: Int)

    private fun writeFrame(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        var crc = 0
        for (b in data) crc += (b.toInt() and 0xFF)
        val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(data.size)
        buf.putInt(crc)
        buf.putInt(command.inv())
        if (data.isNotEmpty()) buf.put(data)
        out.write(buf.array())
        out.flush()
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var total = 0
        while (total < size) {
            val r = input.read(buffer, total, size - total)
            if (r == -1) throw IllegalStateException("unexpected EOF")
            total += r
        }
        return buffer
    }

    private fun readFrame(input: InputStream): Header {
        val bytes = readFully(input, 24)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLength = buf.int
        buf.int // crc, unchecked
        buf.int // magic, unchecked
        return Header(command, arg0, arg1, dataLength)
    }

    // Starts a fake daemon on an ephemeral port, running `handler` against the first accepted
    // connection on a background thread. Returns the bound port.
    private fun startFakeDaemon(handler: (Socket) -> Unit): Pair<ServerSocket, Int> {
        val server = ServerSocket(0)
        val thread = Thread {
            try {
                server.accept().use { socket -> handler(socket) }
            } catch (e: Exception) {
                // Test-only fixture: connection-refused / early-close tests intentionally
                // trigger this by closing the server before the client connects.
            }
        }
        thread.isDaemon = true
        thread.start()
        return server to server.localPort
    }

    @Test
    fun testInstallWithAdbLoopbackSendsExecAndParsesSuccess() {
        var receivedCommand: String? = null
        val (server, port) = startFakeDaemon { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // CNXN — no auth challenge, accept immediately.
            val cnxn = readFrame(input)
            readFully(input, cnxn.dataLength)
            writeFrame(output, Cmd.A_CNXN, 0x01000000, 4096, "device::".toByteArray())

            // OPEN — capture the exec: command, then respond Success and close.
            val open = readFrame(input)
            val openData = readFully(input, open.dataLength)
            receivedCommand = String(openData, Charsets.UTF_8).trimEnd('\u0000').removePrefix("exec:")

            writeFrame(output, Cmd.A_OKAY, 1, open.arg0, ByteArray(0))
            writeFrame(output, Cmd.A_WRTE, 1, open.arg0, "Success\n".toByteArray())
            // Drain the client's A_OKAY ack for this WRTE and its final A_CLSE before the
            // enclosing .use{} closes the socket — otherwise the client's own writes race the
            // server teardown and intermittently see a broken pipe instead of completing cleanly.
            readFrame(input) // client's A_OKAY ack
            readFrame(input) // client's A_CLSE
        }

        val apk = kotlin.io.path.createTempFile(prefix = "test", suffix = ".apk").toFile()
        apk.writeBytes(byteArrayOf(1, 2, 3, 4))
        apk.deleteOnExit()

        val result = runBlocking {
            AdbLoopbackInstaller.installWithAdbLoopback(apk, host = "127.0.0.1", port = port)
        }
        server.close()

        assertTrue("expected success, got $result", result.isSuccess)
        assertTrue(
            "expected the exec: command to contain pm install, got: $receivedCommand",
            receivedCommand?.contains("pm install") == true
        )
    }

    @Test
    fun testRunShellCommandCompletesAuthRoundTrip() {
        var receivedCommand: String? = null
        val (server, port) = startFakeDaemon { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // CNXN — challenge with AUTH first, forcing the client through the sign+pubkey path.
            val cnxn = readFrame(input)
            readFully(input, cnxn.dataLength)
            writeFrame(output, Cmd.A_AUTH, 1 /* AUTH_TOKEN */, 0, ByteArray(20))

            // Client replies AUTH_SIGNATURE with a key we've never seen — reject by asking again,
            // forcing the client to fall back to sending its RSAPUBLICKEY.
            val sigFrame = readFrame(input)
            readFully(input, sigFrame.dataLength)
            writeFrame(output, Cmd.A_AUTH, 1 /* AUTH_TOKEN */, 0, ByteArray(20))

            // Client now sends AUTH_RSAPUBLICKEY — accept it and complete the handshake.
            val pubKeyFrame = readFrame(input)
            readFully(input, pubKeyFrame.dataLength)
            writeFrame(output, Cmd.A_CNXN, 0x01000000, 4096, "device::".toByteArray())

            // OPEN — capture the arbitrary shell command, stream output across two WRTEs, then
            // close naturally (no "Success" marker — exercises the generic capture-until-CLSE path).
            val open = readFrame(input)
            val openData = readFully(input, open.dataLength)
            receivedCommand = String(openData, Charsets.UTF_8).trimEnd('\u0000').removePrefix("exec:")

            writeFrame(output, Cmd.A_OKAY, 1, open.arg0, ByteArray(0))
            writeFrame(output, Cmd.A_WRTE, 1, open.arg0, "com.facebook.alohaservices.presence/x:".toByteArray())
            readFrame(input) // client's OKAY ack
            writeFrame(output, Cmd.A_WRTE, 1, open.arg0, "com.cfox.droidmesh/AutoInstallService\n".toByteArray())
            readFrame(input) // client's OKAY ack
            writeFrame(output, Cmd.A_CLSE, 1, open.arg0, ByteArray(0))
        }

        val result = runBlocking {
            AdbLoopbackInstaller.runShellCommand(
                "settings get secure enabled_accessibility_services",
                host = "127.0.0.1",
                port = port
            )
        }
        server.close()

        assertTrue("expected success, got $result", result.isSuccess)
        assertTrue(
            "expected the full concatenated output across both WRTEs, got: ${result.getOrNull()}",
            result.getOrNull()?.contains("com.cfox.droidmesh/AutoInstallService") == true
        )
        assertTrue(
            "expected the literal requested command, got: $receivedCommand",
            receivedCommand == "settings get secure enabled_accessibility_services"
        )
    }

    @Test
    fun testRunShellCommandFailsGracefullyWhenNothingListening() {
        // Bind, discover a free port, then close before the client ever connects. Uses an
        // allowlisted command (INST-BEHAVE-015) so this test still proves a genuine
        // connection-refused failure, distinct from the allowlist-rejection tests below.
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()

        val result = runBlocking {
            AdbLoopbackInstaller.runShellCommand(
                "settings put secure accessibility_enabled 1",
                host = "127.0.0.1",
                port = port
            )
        }

        assertFalse("expected failure when nothing is listening, got $result", result.isSuccess)
        assertFalse(
            "expected a connection failure, not an allowlist rejection",
            result.exceptionOrNull() is SecurityException
        )
    }

    // [PROGRAMMATIC] INST-TEST-019: gitea#70 -- isAllowedShellCommand accepts every exact
    // hardcoded command ProvisioningAuditor.repair()/repairAccessibility() currently sends, plus
    // the one fixed-prefix pattern for the runtime-varying merged accessibility-services value.
    @Test
    fun testIsAllowedShellCommandAcceptsKnownProvisioningCommands() {
        assertTrue(
            AdbLoopbackInstaller.isAllowedShellCommand(
                "appops set com.cfox.droidmesh REQUEST_INSTALL_PACKAGES allow"
            )
        )
        assertTrue(
            AdbLoopbackInstaller.isAllowedShellCommand(
                "dumpsys deviceidle whitelist +com.cfox.droidmesh"
            )
        )
        assertTrue(
            AdbLoopbackInstaller.isAllowedShellCommand(
                "settings get secure enabled_accessibility_services"
            )
        )
        assertTrue(
            AdbLoopbackInstaller.isAllowedShellCommand(
                "settings put secure accessibility_enabled 1"
            )
        )
        // The one call site whose value varies at runtime (repairAccessibility()'s re-merged
        // colon-joined component list) -- safe-charset value must still be accepted.
        assertTrue(
            AdbLoopbackInstaller.isAllowedShellCommand(
                "settings put secure enabled_accessibility_services " +
                    "com.cfox.droidmesh/com.cfox.droidmesh.service.AutoInstallService"
            )
        )
        assertTrue(
            "multiple colon-joined pre-existing OEM services plus DroidMesh's own must be accepted",
            AdbLoopbackInstaller.isAllowedShellCommand(
                "settings put secure enabled_accessibility_services " +
                    "com.meta.horizon/com.meta.horizon.PresenceService:" +
                    "com.cfox.droidmesh/com.cfox.droidmesh.service.AutoInstallService"
            )
        )
    }

    // [PROGRAMMATIC] INST-TEST-035 (gitea#89): Android 13+/14 resets the ACCESS_RESTRICTED_SETTINGS
    // app-op to `deny` for a sideloaded app on every install/update, which silently undoes the
    // accessibility grant even when it was written correctly -- see
    // android14-restricted-settings-sideload memory. ProvisioningAuditor.repairAccessibility() now
    // clears that app-op for DroidMesh's own package before writing the accessibility settings, so
    // the exact command needs a spot on the allowlist.
    @Test
    fun testIsAllowedShellCommandAcceptsAccessRestrictedSettingsClear() {
        assertTrue(
            AdbLoopbackInstaller.isAllowedShellCommand(
                "appops set com.cfox.droidmesh ACCESS_RESTRICTED_SETTINGS allow"
            )
        )
    }

    // [PROGRAMMATIC] INST-TEST-038 (INST-BEHAVE-022, gitea#100): Chris decided DroidMesh's
    // ACCESS_RESTRICTED_SETTINGS auto-clear (INST-BEHAVE-020) should widen to managed-app packages
    // too, not just DroidMesh's own -- reverses INST-TEST-035's prior negative case. The op joins
    // the generic per-app APP_OPS catalog instead of staying an exact string, exactly like
    // SYSTEM_ALERT_WINDOW/WRITE_SETTINGS/GET_USAGE_STATS/REQUEST_INSTALL_PACKAGES already do.
    @Test
    fun testIsAllowedShellCommandAcceptsAccessRestrictedSettingsClearForManagedApp() {
        assertTrue(
            AdbLoopbackInstaller.isAllowedShellCommand(
                "appops set me.jxl.kiosk_satellite ACCESS_RESTRICTED_SETTINGS allow"
            )
        )
        assertTrue(
            "DroidMesh's own package must still be accepted now that it's the generic catalog, " +
                "not the retired exact string",
            AdbLoopbackInstaller.isAllowedShellCommand(
                "appops set com.cfox.droidmesh ACCESS_RESTRICTED_SETTINGS allow"
            )
        )
    }

    // [PROGRAMMATIC] INST-TEST-039 (INST-BEHAVE-022, negative, gitea#100): widening the catalog
    // must not widen the charset or the fixed "allow" mode -- only the op name itself is new.
    @Test
    fun testIsAllowedShellCommandRejectsMalformedAccessRestrictedSettingsForManagedApp() {
        assertFalse(
            "deny is not allow -- the generic APP_OPS pattern is allow-only",
            AdbLoopbackInstaller.isAllowedShellCommand(
                "appops set me.jxl.kiosk_satellite ACCESS_RESTRICTED_SETTINGS deny"
            )
        )
        assertFalse(
            "an injected command after an otherwise-valid managed-app clear must still be rejected",
            AdbLoopbackInstaller.isAllowedShellCommand(
                "appops set me.jxl.kiosk_satellite ACCESS_RESTRICTED_SETTINGS allow; rm -rf /data"
            )
        )
        assertFalse(
            "a package name carrying a shell metacharacter must still be rejected",
            AdbLoopbackInstaller.isAllowedShellCommand(
                "appops set me.jxl.kiosk_satellite; reboot ACCESS_RESTRICTED_SETTINGS allow"
            )
        )
    }

    // [PROGRAMMATIC] INST-TEST-020 (negative): gitea#70 -- anything not on the allowlist, and the
    // one prefix pattern with an unsafe (shell-metacharacter-bearing) value, must be rejected.
    // Without this test, deleting isAllowedShellCommand entirely (or making it always return true)
    // would still pass every other test in this file, since they only ever pass allowlisted
    // commands.
    @Test
    fun testIsAllowedShellCommandRejectsUnknownAndInjectedCommands() {
        assertFalse(AdbLoopbackInstaller.isAllowedShellCommand("echo hi"))
        assertFalse(AdbLoopbackInstaller.isAllowedShellCommand("reboot -p"))
        assertFalse(AdbLoopbackInstaller.isAllowedShellCommand("rm -rf /data"))
        assertFalse(
            "shell metacharacters in the runtime-varying value must be rejected, not merely the fixed prefix",
            AdbLoopbackInstaller.isAllowedShellCommand(
                "settings put secure enabled_accessibility_services com.evil/x; rm -rf /data"
            )
        )
        assertFalse(
            AdbLoopbackInstaller.isAllowedShellCommand(
                "settings put secure enabled_accessibility_services `reboot`"
            )
        )
        assertFalse(
            AdbLoopbackInstaller.isAllowedShellCommand(
                "settings put secure enabled_accessibility_services com.a/b | com.c/d"
            )
        )
        assertFalse(
            "a near-miss prefix (extra trailing text before the real settings key) must not slip through",
            AdbLoopbackInstaller.isAllowedShellCommand(
                "settings put secure enabled_accessibility_services_evil com.a/b"
            )
        )
        // Falsification guard: without this, weakening the exact-string-set membership check
        // (ALLOWED_EXACT_SHELL_COMMANDS.contains(command)) to a prefix check
        // (ALLOWED_EXACT_SHELL_COMMANDS.any { command.startsWith(it) }) would still pass every
        // accept-test above (each exact literal trivially starts with itself) while letting a
        // malicious suffix appended after an already-allowed literal slip through as a real
        // command injection.
        assertFalse(
            "appending an injection after an otherwise-allowed exact command must be rejected",
            AdbLoopbackInstaller.isAllowedShellCommand(
                "settings put secure accessibility_enabled 1; rm -rf /data"
            )
        )
        assertFalse(
            AdbLoopbackInstaller.isAllowedShellCommand(
                "settings get secure enabled_accessibility_services && reboot"
            )
        )
        // DEPRECATED (INST-TEST-035's negative half, superseded by INST-TEST-038, gitea#100):
        // ACCESS_RESTRICTED_SETTINGS clearing for a managed-app package used to be rejected here on
        // purpose. Chris (2026-09-12) decided DroidMesh should auto-clear this for the App Library
        // too -- see INST-BEHAVE-022 -- so this case is now a positive assertion, not a negative one.
    }

    // [PROGRAMMATIC] INST-TEST-021: gitea#70 -- runShellCommand rejects a disallowed command
    // before ever opening a socket connection. Nothing is listening on this port at all, so if
    // runShellCommand tried to connect first it would surface a connection-refused failure
    // instead -- asserting specifically on SecurityException naming the rejected command proves
    // the allowlist check runs before any network I/O.
    @Test
    fun testRunShellCommandRejectsDisallowedCommandBeforeOpeningSocket() {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()

        val result = runBlocking {
            AdbLoopbackInstaller.runShellCommand("reboot -p", host = "127.0.0.1", port = port)
        }

        assertFalse("expected failure for a disallowed command", result.isSuccess)
        assertTrue(
            "expected a SecurityException naming the rejected command, got: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is SecurityException
        )
        assertTrue(
            "expected the exception message to name the rejected command",
            (result.exceptionOrNull()?.message ?: "").contains("reboot -p")
        )
    }

    // [PROGRAMMATIC] INST-TEST-027: the six per-app repair shapes ASET-BEHAVE-005 needs. These
    // name a managed app's package rather than DroidMesh's own, so they can't be exact strings --
    // they're anchored full-command regexes with a metacharacter-free charset.
    @Test
    fun testIsAllowedShellCommandAcceptsPerAppRepairCommands() {
        val accepted = listOf(
            "cmd notification allow_listener com.spocky.projengmenu/com.spocky.projengmenu.services.NotificationListener",
            "dumpsys deviceidle whitelist +dev.vodik7.tvquickactions.free",
            "appops set me.jxl.kiosk_satellite SYSTEM_ALERT_WINDOW allow",
            "appops set me.jxl.kiosk_satellite GET_USAGE_STATS allow",
            "appops set me.jxl.kiosk_satellite WRITE_SETTINGS allow",
            "appops set me.jxl.kiosk_satellite REQUEST_INSTALL_PACKAGES allow",
            "appops get me.jxl.kiosk_satellite SYSTEM_ALERT_WINDOW",
            "pm set-home-activity com.spocky.projengmenu/com.spocky.projengmenu.ui.home.MainActivity",
            "pm grant com.spocky.projengmenu android.permission.RECORD_AUDIO"
        )
        for (command in accepted) {
            assertTrue("must accept: $command", AdbLoopbackInstaller.isAllowedShellCommand(command))
        }
        // Regression: widening the allowlist must not drop the original provisioning commands.
        for (command in listOf(
            "appops set com.cfox.droidmesh REQUEST_INSTALL_PACKAGES allow",
            "dumpsys deviceidle whitelist +com.cfox.droidmesh",
            "settings get secure enabled_accessibility_services",
            "settings put secure accessibility_enabled 1",
            "settings put secure enabled_accessibility_services com.cfox.droidmesh/com.cfox.droidmesh.service.AutoInstallService"
        )) {
            assertTrue("regression, must still accept: $command", AdbLoopbackInstaller.isAllowedShellCommand(command))
        }
    }

    // [PROGRAMMATIC] INST-TEST-028 (negative): every new shape stays closed against injection,
    // extra/missing arguments, off-catalog ops, and near-miss verbs.
    @Test
    fun testIsAllowedShellCommandRejectsInjectedPerAppRepairCommands() {
        val rejected = listOf(
            // Injection through each parameterized position.
            "pm grant com.spocky.projengmenu android.permission.RECORD_AUDIO; rm -rf /data",
            "pm grant com.spocky.projengmenu android.permission.RECORD_AUDIO && reboot",
            "pm grant com.spocky.projengmenu android.permission.RECORD_AUDIO | sh",
            "pm grant com.spocky.projengmenu `reboot`",
            "pm grant com.spocky.projengmenu \$(reboot)",
            "dumpsys deviceidle whitelist +dev.vodik7.tvquickactions.free; reboot",
            "appops set me.jxl.kiosk_satellite SYSTEM_ALERT_WINDOW allow; reboot",
            "cmd notification allow_listener com.spocky.projengmenu/.L;reboot",
            "pm set-home-activity com.spocky.projengmenu/.Home && reboot",
            // Extra or missing arguments.
            "pm grant com.spocky.projengmenu android.permission.RECORD_AUDIO extra",
            "pm grant --user 0 com.spocky.projengmenu android.permission.RECORD_AUDIO",
            "pm grant com.spocky.projengmenu",
            "appops set me.jxl.kiosk_satellite SYSTEM_ALERT_WINDOW",
            "appops get me.jxl.kiosk_satellite SYSTEM_ALERT_WINDOW extra",
            "cmd notification allow_listener",
            "dumpsys deviceidle whitelist +",
            // Off-catalog / wrong-case app ops.
            "appops set me.jxl.kiosk_satellite RUN_IN_BACKGROUND allow",
            "appops set me.jxl.kiosk_satellite system_alert_window allow",
            "appops get me.jxl.kiosk_satellite RUN_ANY_IN_BACKGROUND",
            // Metacharacters inside a package or class name.
            "pm grant com.spocky projengmenu android.permission.RECORD_AUDIO",
            "dumpsys deviceidle whitelist +com.a:com.b",
            "cmd notification allow_listener com.a/b/c",
            "cmd notification allow_listener 'com.a/b'",
            "pm set-home-activity com.a/com.b:com.c/com.d",
            // Near-miss verbs that would be destructive or mode-flipping.
            "pm revoke com.spocky.projengmenu android.permission.RECORD_AUDIO",
            "pm uninstall com.spocky.projengmenu",
            "appops set me.jxl.kiosk_satellite SYSTEM_ALERT_WINDOW deny",
            "appops set me.jxl.kiosk_satellite SYSTEM_ALERT_WINDOW ignore",
            "dumpsys deviceidle whitelist -dev.vodik7.tvquickactions.free",
            "cmd notification disallow_listener com.spocky.projengmenu/.L",
            "cmd package install com.spocky.projengmenu"
        )
        for (command in rejected) {
            assertFalse("must reject: $command", AdbLoopbackInstaller.isAllowedShellCommand(command))
        }
    }

    // --- INST-TEST-032: the AUTH handshake against a daemon that checks its inputs the way real
    // adbd does. The pre-existing auth round-trip test above accepts any bytes at all as an
    // RSAPUBLICKEY payload, which is precisely why a key adbd logged as "Invalid base64 key"
    // passed the suite and failed on every device (gitea#85).

    // ASN.1 DigestInfo header for SHA-1, stated here independently of the production constant.
    private val sha1DigestInfoPrefix = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    // The same keypair the client will present: AdbAuthKeys is the single source for both.
    private fun clientKeyPair(): java.security.KeyPair {
        val dir = kotlin.io.path.createTempDirectory(prefix = "adbkeys-wire").toFile()
        dir.deleteOnExit()
        AdbAuthKeys.init(dir)
        return AdbAuthKeys.keyPair()
    }

    @Test
    fun testRunShellCommandAuthenticatesWithSignatureAloneWhenTheKeyIsKnown() {
        val keyPair = clientKeyPair()
        val publicKey = keyPair.public as java.security.interfaces.RSAPublicKey
        val token = ByteArray(20) { (it * 7 + 3).toByte() }

        var signatureAccepted = false
        var frameAfterAuth = 0
        var receivedCommand: String? = null

        val (server, port) = startFakeDaemon { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val cnxn = readFrame(input)
            readFully(input, cnxn.dataLength)
            writeFrame(output, Cmd.A_AUTH, 1 /* AUTH_TOKEN */, 0, token)

            // Verify exactly as adbd does: RSA_verify(NID_sha1, token, ...) is PKCS#1 v1.5 over
            // DigestInfo(SHA-1, token), so recovering the padded block must yield that DigestInfo.
            val sigFrame = readFrame(input)
            val signature = readFully(input, sigFrame.dataLength)
            val recovered = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(javax.crypto.Cipher.DECRYPT_MODE, publicKey)
                doFinal(signature)
            }
            signatureAccepted = sigFrame.arg0 == 2 && recovered.contentEquals(sha1DigestInfoPrefix + token)
            if (!signatureAccepted) throw IllegalStateException("signature rejected")

            writeFrame(output, Cmd.A_CNXN, 0x01000000, 4096, "device::".toByteArray())

            // A correctly signed token means no public key is ever sent: the next frame is OPEN.
            val open = readFrame(input)
            frameAfterAuth = open.command
            val openData = readFully(input, open.dataLength)
            receivedCommand = String(openData, Charsets.UTF_8).trimEnd('\u0000').removePrefix("exec:")

            writeFrame(output, Cmd.A_OKAY, 1, open.arg0, ByteArray(0))
            writeFrame(output, Cmd.A_WRTE, 1, open.arg0, "Added: com.cfox.droidmesh\n".toByteArray())
            readFrame(input) // client OKAY ack
            writeFrame(output, Cmd.A_CLSE, 1, open.arg0, ByteArray(0))
        }

        val result = runBlocking {
            AdbLoopbackInstaller.runShellCommand(
                "dumpsys deviceidle whitelist +com.cfox.droidmesh",
                host = "127.0.0.1",
                port = port
            )
        }
        server.close()

        assertTrue("expected success, got $result", result.isSuccess)
        assertTrue("daemon must accept the SHA-1 DigestInfo signature", signatureAccepted)
        assertEquals("an authorized key must not trigger a pubkey round trip", Cmd.A_OPEN, frameAfterAuth)
        assertEquals("dumpsys deviceidle whitelist +com.cfox.droidmesh", receivedCommand)
        assertTrue(
            "expected the daemon output, got ${result.getOrNull()}",
            result.getOrNull()?.contains("Added: com.cfox.droidmesh") == true
        )
    }

    @Test
    fun testRunShellCommandSendsAnAndroidPubkeyAndReportsPendingAuthorization() {
        val keyPair = clientKeyPair()
        val publicKey = keyPair.public as java.security.interfaces.RSAPublicKey
        val token = ByteArray(20) { (it + 1).toByte() }

        var payloadBlobSize = -1
        var payloadModulusMatches = false
        var payloadIdentity: String? = null

        val (server, port) = startFakeDaemon { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val cnxn = readFrame(input)
            readFully(input, cnxn.dataLength)
            writeFrame(output, Cmd.A_AUTH, 1 /* AUTH_TOKEN */, 0, token)

            // Reject the signature (key not in adb_keys yet), forcing the RSAPUBLICKEY path.
            val sigFrame = readFrame(input)
            readFully(input, sigFrame.dataLength)
            writeFrame(output, Cmd.A_AUTH, 1 /* AUTH_TOKEN */, 0, token)

            val pubKeyFrame = readFrame(input)
            val payload = readFully(input, pubKeyFrame.dataLength)
            val text = String(payload, Charsets.UTF_8).trimEnd('\u0000')
            payloadIdentity = text.substringAfter(' ', "")
            val blob = java.util.Base64.getDecoder().decode(text.substringBefore(' '))
            payloadBlobSize = blob.size
            payloadModulusMatches = AdbAuthKeys.decodePublicKey(blob).first == publicKey.modulus

            // Real adbd answers nothing until somebody taps "Allow debugging" on the device.
            Thread.sleep(1500)
        }

        val result = runBlocking {
            AdbLoopbackInstaller.runShellCommand(
                "dumpsys deviceidle whitelist +com.cfox.droidmesh",
                host = "127.0.0.1",
                port = port,
                readTimeoutMs = 400
            )
        }
        server.close()

        assertEquals("payload must be a 524-byte android_pubkey blob", 524, payloadBlobSize)
        assertTrue("blob must carry this client's modulus", payloadModulusMatches)
        assertEquals("droidmesh@localhost", payloadIdentity)
        assertFalse("expected failure while authorization is pending, got $result", result.isSuccess)
        assertTrue(
            "a pending on-screen authorization must be reported as such, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is AdbAuthorizationPendingException
        )
        assertTrue(
            "the message must tell the operator where to look",
            result.exceptionOrNull()?.message?.contains("Allow debugging from this computer?") == true
        )
    }
}
