package com.cfox.droidmesh.installer

import com.cfox.droidmesh.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AdbLoopbackInstaller {

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257

    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    // Shown by the device's "Allow debugging from this computer?" dialog as the key's owner.
    private const val AUTH_IDENTITY = "droidmesh@localhost"

    private const val ADB_VERSION = 0x01000000
    private const val MAX_DATA = 4096
    private const val DEFAULT_HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 5555

    // Long enough to cover a streaming `pm install` and, on first use, a person walking to the TV
    // to answer the ADB authorization prompt. Overridable so tests can exercise the timeout path.
    internal const val DEFAULT_READ_TIMEOUT_MS = 60000

    suspend fun installWithAdbLoopback(
        apkFile: File,
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("APK file invalid"))
        }

        Logger.i("Attempting local loopback ADB install (-r -d) for ${apkFile.name} on $host:$port")
        val cmd = "cat \"${apkFile.absolutePath}\" | pm install -r -d -S ${apkFile.length()}"
        runAdbSession(host, port, cmd, earlyStopOnSubstring = "Success")
    }

    // INST-BEHAVE-015 (gitea#70): runShellCommand is a generic, fully-unvalidated shell-exec
    // primitive over the loopback ADB session -- it currently has exactly one caller
    // (ProvisioningAuditor's repair flow, PROV-BEHAVE-004/006) passing only these hardcoded
    // strings, but a future caller that forwarded request-derived data into it would inherit full
    // command execution risk with no additional review needed to notice. Fail closed at this
    // function's own boundary: exact match against the known-legitimate command set, or -- for the
    // one call site whose content varies at runtime (repairAccessibility()'s re-merged
    // enabled_accessibility_services value) -- a fixed prefix followed by a charset-restricted
    // value, mirroring ApkDownloader.isSafeApkFileName's whitelist-regex style (gitea#53).
    // INST-BEHAVE-020 (gitea#89): Android 13+/14 resets ACCESS_RESTRICTED_SETTINGS to `deny` for a
    // sideloaded app on every install/update, which silently strips DroidMesh's own accessibility
    // grant back out even when it was written correctly -- see
    // android14-restricted-settings-sideload memory. Exact string, not the generic per-app
    // APP_OPS regex below: it must only ever apply to DroidMesh's own package, never a managed
    // app's (see INST-TEST-035's negative case).
    private val ALLOWED_EXACT_SHELL_COMMANDS = setOf(
        "appops set com.cfox.droidmesh REQUEST_INSTALL_PACKAGES allow",
        "appops set com.cfox.droidmesh ACCESS_RESTRICTED_SETTINGS allow",
        "dumpsys deviceidle whitelist +com.cfox.droidmesh",
        "settings get secure enabled_accessibility_services",
        "settings put secure accessibility_enabled 1"
    )

    private const val ACCESSIBILITY_SERVICES_PUT_PREFIX =
        "settings put secure enabled_accessibility_services "

    // Matches colon-joined "package/Class" component names -- the only shape
    // ProvisioningAuditor.mergeAccessibilityServices ever produces -- and rejects every shell
    // metacharacter (quotes, `;`, `|`, backticks, spaces) outright rather than trying to escape them.
    private val ACCESSIBILITY_SERVICES_VALUE_REGEX = Regex("^[A-Za-z0-9_./:]+$")

    // INST-BEHAVE-015 amended 2026-09-11 (REQ-ADMIN-011 / ASET-BEHAVE-005): per-app settings
    // repair needs six more shapes, each naming a *managed app's* package rather than DroidMesh's
    // own, so they can't be exact strings. They are full-command patterns, not a relaxation of the
    // exact set: Kotlin's Regex.matches requires the entire string to match, the charset excludes
    // whitespace and every shell metacharacter, and the app-op position is a literal alternation
    // over the four-op catalog -- so no accepted command can carry a chained command, a
    // substitution, an extra argument, or an off-catalog op.
    private const val IDENT = "[A-Za-z0-9_.]+"
    private const val APP_OPS =
        "(?:SYSTEM_ALERT_WINDOW|GET_USAGE_STATS|WRITE_SETTINGS|REQUEST_INSTALL_PACKAGES)"

    private val ALLOWED_COMMAND_PATTERNS = listOf(
        Regex("cmd notification allow_listener $IDENT/$IDENT"),
        Regex("dumpsys deviceidle whitelist \\+$IDENT"),
        Regex("appops set $IDENT $APP_OPS allow"),
        Regex("appops get $IDENT $APP_OPS"),
        Regex("pm set-home-activity $IDENT/$IDENT"),
        Regex("pm grant $IDENT $IDENT")
    )

    internal fun isAllowedShellCommand(command: String): Boolean {
        if (ALLOWED_EXACT_SHELL_COMMANDS.contains(command)) {
            return true
        }
        if (command.startsWith(ACCESSIBILITY_SERVICES_PUT_PREFIX)) {
            val value = command.removePrefix(ACCESSIBILITY_SERVICES_PUT_PREFIX)
            return ACCESSIBILITY_SERVICES_VALUE_REGEX.matches(value)
        }
        return ALLOWED_COMMAND_PATTERNS.any { it.matches(command) }
    }

    // INST-BEHAVE-008: generic shell command execution over the same loopback ADB session
    // installWithAdbLoopback already used, for callers other than the APK installer (e.g.
    // PROV-BEHAVE-004's provisioning repair). Waits for the remote to close the exec stream
    // (A_CLSE) rather than early-exiting on any particular substring, since arbitrary command
    // output has no fixed "done" marker the way `pm install` does.
    suspend fun runShellCommand(
        command: String,
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isAllowedShellCommand(command)) {
            val err = "Rejected non-allowlisted shell command: $command"
            Logger.e(err)
            return@withContext Result.failure(SecurityException(err))
        }
        Logger.i("Running loopback ADB shell command on $host:$port: $command")
        runAdbSession(host, port, command, earlyStopOnSubstring = null, readTimeoutMs = readTimeoutMs)
    }

    // Shared CNXN/AUTH/OPEN/WRTE/CLSE session: connects, authenticates if challenged, opens an
    // `exec:<command>` stream, and captures its output. `earlyStopOnSubstring` preserves
    // installWithAdbLoopback's original behavior of closing the stream the moment the output
    // contains that substring (case-insensitive) instead of waiting for the remote to close it —
    // left null for runShellCommand, which has no such fixed marker to watch for.
    private fun runAdbSession(
        host: String,
        port: Int,
        command: String,
        earlyStopOnSubstring: String?,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
    ): Result<String> {
        // Set once the public key is on the wire and the device is being asked to authorize it:
        // the read that blocks next is waiting on a person, not on the network (PROV-BEHAVE-010).
        var awaitingAuthorization = false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2000)
                socket.soTimeout = readTimeoutMs

                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                // 1. Send CNXN
                val sysInfo = "host::\u0000".toByteArray(Charsets.UTF_8)
                writeMessage(output, A_CNXN, ADB_VERSION, MAX_DATA, sysInfo)

                // 2. Read CNXN or AUTH response
                var header = readHeader(input) ?: throw IllegalStateException("No response from ADB daemon")

                if (header.command == A_AUTH) {
                    Logger.i("ADB daemon requested authentication, handling auth challenge")
                    val tokenData = if (header.dataLength > 0) readFully(input, header.dataLength) ?: ByteArray(0) else ByteArray(0)

                    // INST-BEHAVE-018: the persisted keypair, so a single on-screen authorization
                    // keeps working across app restarts instead of re-prompting every process.
                    val keyPair = AdbAuthKeys.keyPair()

                    // INST-BEHAVE-017: PKCS#1 over DigestInfo(SHA-1, token) - the only signature
                    // shape adbd's RSA_verify(NID_sha1, ...) accepts. Succeeds outright once the
                    // key is in the device's adb_keys, with no prompt and no pubkey round trip.
                    try {
                        val signature = AdbAuthKeys.signToken(keyPair.private, tokenData)
                        writeMessage(output, A_AUTH, AUTH_SIGNATURE, 0, signature)

                        header = readHeader(input) ?: throw IllegalStateException("No response after ADB signature")
                    } catch (e: Exception) {
                        Logger.w("Could not sign ADB token: ${e.message}")
                    }

                    // Still AUTH means adbd does not know this key yet: send it and let the device
                    // ask its user. INST-BEHAVE-017: as the `android_pubkey` struct, base64'd -
                    // an X.509 SubjectPublicKeyInfo makes adbd log "Invalid base64 key" and go
                    // silent, which reads to the caller as an unexplained 60s timeout (gitea#85).
                    if (header.command == A_AUTH) {
                        readFully(input, header.dataLength)
                        val publicKey = keyPair.public as java.security.interfaces.RSAPublicKey
                        writeMessage(
                            output,
                            A_AUTH,
                            AUTH_RSAPUBLICKEY,
                            0,
                            AdbAuthKeys.publicKeyAuthPayload(publicKey, AUTH_IDENTITY)
                        )
                        Logger.i(
                            "Sent ADB public key; awaiting on-screen authorization " +
                                "(\"Allow debugging from this computer?\") on the device"
                        )
                        awaitingAuthorization = true

                        header = readHeader(input) ?: throw IllegalStateException("No response after ADB public key")
                        awaitingAuthorization = false
                    }
                }

                if (header.command != A_CNXN) {
                    throw IllegalStateException("Expected CNXN response, got 0x${Integer.toHexString(header.command)}")
                }
                readFully(input, header.dataLength)

                // 3. Open exec stream for the requested command
                val localId = 1
                val cmdBytes = "exec:$command\u0000".toByteArray(Charsets.UTF_8)
                writeMessage(output, A_OPEN, localId, 0, cmdBytes)

                val responseBuffer = ByteArrayOutputStream()
                var remoteId = 0

                while (true) {
                    val msg = readHeader(input) ?: break
                    val data = if (msg.dataLength > 0) readFully(input, msg.dataLength) ?: ByteArray(0) else ByteArray(0)

                    when (msg.command) {
                        A_OKAY -> {
                            remoteId = msg.arg0
                        }
                        A_WRTE -> {
                            responseBuffer.write(data)
                            // Ack with A_OKAY
                            writeMessage(output, A_OKAY, localId, remoteId, ByteArray(0))
                            if (earlyStopOnSubstring != null &&
                                responseBuffer.toString("UTF-8").contains(earlyStopOnSubstring, ignoreCase = true)
                            ) {
                                Logger.i("Received '$earlyStopOnSubstring' from ADB daemon, closing stream")
                                writeMessage(output, A_CLSE, localId, remoteId, ByteArray(0))
                                break
                            }
                        }
                        A_CLSE -> {
                            writeMessage(output, A_CLSE, localId, remoteId, ByteArray(0))
                            break
                        }
                    }
                }

                val resultOutput = responseBuffer.toString("UTF-8").trim()
                Logger.i("ADB loopback session response: $resultOutput")

                if (earlyStopOnSubstring != null) {
                    if (resultOutput.contains(earlyStopOnSubstring, ignoreCase = true)) {
                        Result.success(resultOutput)
                    } else {
                        Result.failure(IllegalStateException("ADB command failed: $resultOutput"))
                    }
                } else {
                    Result.success(resultOutput)
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            if (awaitingAuthorization) {
                Logger.w("ADB loopback session timed out awaiting on-screen authorization")
                Result.failure(AdbAuthorizationPendingException())
            } else {
                Logger.w("ADB loopback session failed (${e.message})")
                Result.failure(e)
            }
        } catch (e: Exception) {
            Logger.w("ADB loopback session failed (${e.message})")
            Result.failure(e)
        }
    }

    private data class AdbHeader(val command: Int, val arg0: Int, val arg1: Int, val dataLength: Int, val dataCrc: Int, val magic: Int)

    private fun writeMessage(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val crc = calculateCrc32(data)
        val magic = command.inv()
        val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(data.size)
        buf.putInt(crc)
        buf.putInt(magic)
        if (data.isNotEmpty()) {
            buf.put(data)
        }
        out.write(buf.array())
        out.flush()
    }

    private fun readHeader(input: InputStream): AdbHeader? {
        val bytes = readFully(input, 24) ?: return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return AdbHeader(
            command = buf.int,
            arg0 = buf.int,
            arg1 = buf.int,
            dataLength = buf.int,
            dataCrc = buf.int,
            magic = buf.int
        )
    }

    private fun readFully(input: InputStream, size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var totalRead = 0
        while (totalRead < size) {
            val r = input.read(buffer, totalRead, size - totalRead)
            if (r == -1) {
                if (totalRead == 0) return null
                throw IllegalStateException("Unexpected EOF reading $size bytes")
            }
            totalRead += r
        }
        return buffer
    }

    private fun calculateCrc32(data: ByteArray): Int {
        var sum = 0
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }
}
