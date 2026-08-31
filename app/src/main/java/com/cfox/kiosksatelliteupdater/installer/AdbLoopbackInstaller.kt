package com.cfox.kiosksatelliteupdater.installer

import com.cfox.kiosksatelliteupdater.utils.Logger
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

    suspend fun installWithAdbLoopback(apkFile: File): Result<String> = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("APK file invalid"))
        }

        try {
            Logger.i("Attempting local loopback ADB install (-r -d) for ${apkFile.name} on 127.0.0.1:5555")
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 5555), 2000)
                socket.soTimeout = 60000 // 60s timeout for streaming install

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
                        val pubKeyBytes = android.util.Base64.encode(keyPair.public.encoded, android.util.Base64.NO_WRAP)
                        val pubKeyPayload = (String(pubKeyBytes, Charsets.UTF_8) + " ksu@localhost\u0000").toByteArray(Charsets.UTF_8)
                        writeMessage(output, A_AUTH, 3 /* AUTH_RSAPUBLICKEY */, 0, pubKeyPayload)
                        
                        header = readHeader(input) ?: throw IllegalStateException("No response after ADB public key")
                    }
                }

                if (header.command != A_CNXN) {
                    throw IllegalStateException("Expected CNXN response, got 0x${Integer.toHexString(header.command)}")
                }
                readFully(input, header.dataLength)

                // 3. Open exec stream for: cat "<path>" | pm install -r -d -S <size>
                val localId = 1
                val cmd = "exec:cat \"${apkFile.absolutePath}\" | pm install -r -d -S ${apkFile.length()}\u0000".toByteArray(Charsets.UTF_8)
                writeMessage(output, A_OPEN, localId, 0, cmd)

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
                            if (responseBuffer.toString("UTF-8").contains("Success", ignoreCase = true)) {
                                Logger.i("Received Success from ADB daemon")
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
                Logger.i("ADB loopback install response: $resultOutput")

                if (resultOutput.contains("Success", ignoreCase = true)) {
                    Result.success(resultOutput)
                } else {
                    Result.failure(IllegalStateException("ADB install failed: $resultOutput"))
                }
            }
        } catch (e: Exception) {
            Logger.w("ADB loopback install failed (${e.message})")
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
