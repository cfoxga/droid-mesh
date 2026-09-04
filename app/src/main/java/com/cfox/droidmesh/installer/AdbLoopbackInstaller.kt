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

    private const val ADB_VERSION = 0x01000000
    private const val MAX_DATA = 4096
    private const val DEFAULT_HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 5555

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
    private val ALLOWED_EXACT_SHELL_COMMANDS = setOf(
        "appops set com.cfox.droidmesh REQUEST_INSTALL_PACKAGES allow",
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

    internal fun isAllowedShellCommand(command: String): Boolean {
        if (ALLOWED_EXACT_SHELL_COMMANDS.contains(command)) {
            return true
        }
        if (command.startsWith(ACCESSIBILITY_SERVICES_PUT_PREFIX)) {
            val value = command.removePrefix(ACCESSIBILITY_SERVICES_PUT_PREFIX)
            return ACCESSIBILITY_SERVICES_VALUE_REGEX.matches(value)
        }
        return false
    }

    // INST-BEHAVE-008: generic shell command execution over the same loopback ADB session
    // installWithAdbLoopback already used, for callers other than the APK installer (e.g.
    // PROV-BEHAVE-004's provisioning repair). Waits for the remote to close the exec stream
    // (A_CLSE) rather than early-exiting on any particular substring, since arbitrary command
    // output has no fixed "done" marker the way `pm install` does.
    suspend fun runShellCommand(
        command: String,
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isAllowedShellCommand(command)) {
            val err = "Rejected non-allowlisted shell command: $command"
            Logger.e(err)
            return@withContext Result.failure(SecurityException(err))
        }
        Logger.i("Running loopback ADB shell command on $host:$port: $command")
        runAdbSession(host, port, command, earlyStopOnSubstring = null)
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
        earlyStopOnSubstring: String?
    ): Result<String> {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2000)
                socket.soTimeout = 60000 // 60s timeout for streaming exec

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

                    // Generate or get local RSA keypair
                    val keyPair = getOrCreateKeyPair()

                    // Sign token and send SIGNATURE
                    try {
                        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
                        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keyPair.private)
                        val signature = cipher.doFinal(tokenData)
                        writeMessage(output, A_AUTH, 2 /* AUTH_SIGNATURE */, 0, signature)

                        header = readHeader(input) ?: throw IllegalStateException("No response after ADB signature")
                    } catch (e: Exception) {
                        Logger.w("Could not sign ADB token: ${e.message}")
                    }

                    // If still AUTH, send RSAPUBLICKEY
                    if (header.command == A_AUTH) {
                        readFully(input, header.dataLength)
                        // java.util.Base64 (not android.util.Base64): available since API 26, well
                        // under this app's Min SDK 28, and — unlike the android.* copy — usable
                        // from plain JUnit tests, which is what caught this path having zero real
                        // coverage before INST-TEST-006 (android.util.Base64 silently returns null
                        // under the default unit-test stub, turning this into an NPE two lines down).
                        val pubKeyBytes = java.util.Base64.getEncoder().encode(keyPair.public.encoded)
                        val pubKeyPayload = (String(pubKeyBytes, Charsets.UTF_8) + " ksu@localhost\u0000").toByteArray(Charsets.UTF_8)
                        writeMessage(output, A_AUTH, 3 /* AUTH_RSAPUBLICKEY */, 0, pubKeyPayload)

                        header = readHeader(input) ?: throw IllegalStateException("No response after ADB public key")
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

    private var cachedKeyPair: java.security.KeyPair? = null

    private fun getOrCreateKeyPair(): java.security.KeyPair {
        cachedKeyPair?.let { return it }
        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        cachedKeyPair = kp
        return kp
    }

    private fun calculateCrc32(data: ByteArray): Int {
        var sum = 0
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }
}
