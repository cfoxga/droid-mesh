package com.cfox.droidmesh.installer

import kotlinx.coroutines.runBlocking
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
        // Bind, discover a free port, then close before the client ever connects.
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()

        val result = runBlocking {
            AdbLoopbackInstaller.runShellCommand("echo hi", host = "127.0.0.1", port = port)
        }

        assertFalse("expected failure when nothing is listening, got $result", result.isSuccess)
    }
}
