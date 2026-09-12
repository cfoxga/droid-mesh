package com.cfox.droidmesh.installer

import com.cfox.droidmesh.utils.Logger
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

// INST-BEHAVE-017/018: the client half of adbd's AUTH handshake, in its own object so the two
// wire encodings adbd accepts are unit-testable without a device (AdbLoopbackInstaller's socket
// plumbing is not).
//
// adbd accepts exactly one public-key encoding and exactly one signature encoding, and neither is
// what the JCE hands you by default:
//   * the key is libcrypto_utils' `android_pubkey` struct, base64'd - NOT an X.509
//     SubjectPublicKeyInfo. A SPKI blob makes adbd log "E adbd: Invalid base64 key MIIBIjAN..."
//     and answer nothing at all, so the client blocks until its socket read times out.
//   * the token signature is RSA_sign(NID_sha1, ...), i.e. PKCS#1 v1.5 over the SHA-1 DigestInfo
//     wrapping of the 20-byte token - NOT a raw PKCS#1 encryption of the bare token.
// Both were wrong in 0.1.0 (82), which is why no loopback ADB session could ever authenticate
// (gitea#85).
object AdbAuthKeys {

    const val MODULUS_BITS = 2048
    private const val MODULUS_BYTES = MODULUS_BITS / 8

    // android_pubkey struct: { uint32 modulus_size_words, uint32 n0inv, uint8 modulus[256],
    // uint8 rr[256], uint32 exponent } == 3 * 4 + 2 * 256 == 524 bytes.
    const val ENCODED_KEY_SIZE = 3 * 4 + 2 * MODULUS_BYTES

    private const val PRIVATE_KEY_FILE = "adbkey.pk8"

    // ASN.1 DigestInfo header for a SHA-1 digest (RFC 8017 A.2.4), the prefix BoringSSL's
    // RSA_sign(NID_sha1) prepends before padding.
    private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    private const val TOKEN_SIZE = 20

    private val NUL = byteArrayOf(0)

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    @Volatile
    private var keyDir: File? = null

    // Called once from DroidMeshApp.onCreate with an app-private directory. Kept separate from
    // keyPair() so the encoding logic below stays free of Android dependencies.
    fun init(dir: File) {
        keyDir = dir
    }

    fun keyPair(): KeyPair {
        cachedKeyPair?.let { return it }
        val dir = keyDir
            ?: throw IllegalStateException("AdbAuthKeys.init() was never called - no ADB key directory")
        synchronized(this) {
            cachedKeyPair?.let { return it }
            return loadOrCreate(dir).also { cachedKeyPair = it }
        }
    }

    // INST-BEHAVE-018: loads the persisted keypair, generating and storing one on first use.
    // Deliberately does NOT consult cachedKeyPair: each call models a fresh app process, which is
    // the case that regressed - a per-process key means adbd sees an unknown key every restart and
    // re-prompts on the device screen, which nobody driving the web UI remotely can answer.
    fun loadOrCreate(dir: File): KeyPair {
        val file = File(dir, PRIVATE_KEY_FILE)
        if (file.isFile && file.length() > 0) {
            try {
                return readKeyPair(file)
            } catch (e: Exception) {
                // Corrupt or truncated (e.g. killed mid-write): mint a fresh key rather than
                // leaving ADB auth permanently broken. Costs one more on-screen authorization.
                Logger.w("Stored ADB key unusable (${e.javaClass.simpleName}), generating a new one")
            }
        }
        val keyPair = generateKeyPair()
        persist(keyPair, file)
        return keyPair
    }

    private fun readKeyPair(file: File): KeyPair {
        val factory = KeyFactory.getInstance("RSA")
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(file.readBytes())) as RSAPrivateCrtKey
        val public = factory.generatePublic(RSAPublicKeySpec(private.modulus, private.publicExponent))
        return KeyPair(public, private)
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        // F4 (65537) explicitly: android_pubkey only encodes an exponent of 3 or 65537.
        generator.initialize(RSAKeyGenParameterSpec(MODULUS_BITS, RSAKeyGenParameterSpec.F4))
        return generator.generateKeyPair()
    }

    private fun persist(keyPair: KeyPair, file: File) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(keyPair.private.encoded)
            restrictToOwner(temp)
            if (!temp.renameTo(file)) {
                temp.delete()
                throw IllegalStateException("could not move the new ADB key into place")
            }
            restrictToOwner(file)
            Logger.i("Stored a new loopback ADB client key (one on-screen authorization required)")
        } catch (e: Exception) {
            // A non-persistent key still authenticates for the life of this process; only the
            // one-time authorization stops sticking. Do not fail the caller's repair over it.
            Logger.w("Could not persist the loopback ADB client key: ${e.javaClass.simpleName}")
        }
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    fun encodePublicKey(key: RSAPublicKey): ByteArray = encodePublicKey(key.modulus, key.publicExponent)

    // Mirrors android_pubkey_encode in system/core/libcrypto_utils/android_pubkey.c, including
    // both derived fields: n0inv (-1 / n[0] mod 2^32) and rr ((2^2048)^2 mod n), which adbd's
    // Montgomery arithmetic needs and will not recompute for you.
    internal fun encodePublicKey(modulus: BigInteger, exponent: BigInteger): ByteArray {
        require(modulus.signum() > 0 && modulus.bitLength() == MODULUS_BITS) {
            "ADB public keys need a $MODULUS_BITS-bit modulus, got ${modulus.bitLength()}"
        }
        require(exponent == BigInteger.valueOf(3L) || exponent == BigInteger.valueOf(65537L)) {
            "android_pubkey only encodes exponent 3 or 65537, got $exponent"
        }

        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(modulus.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BITS).modPow(BigInteger.valueOf(2L), modulus)

        val buffer = ByteBuffer.allocate(ENCODED_KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MODULUS_BITS / 32)
        buffer.putInt(n0inv.toInt())
        buffer.put(toLittleEndian(modulus))
        buffer.put(toLittleEndian(rr))
        buffer.putInt(exponent.toInt())
        return buffer.array()
    }

    // The AUTH_RSAPUBLICKEY payload adbd parses: "<base64 android_pubkey> <identity>", NUL
    // terminated. `identity` is what the on-screen authorization dialog attributes the key to.
    fun publicKeyAuthPayload(key: RSAPublicKey, identity: String): ByteArray {
        val encoded = java.util.Base64.getEncoder().encodeToString(encodePublicKey(key))
        return (encoded + " " + identity).toByteArray(Charsets.UTF_8) + NUL
    }

    // PKCS#1 v1.5 signature over DigestInfo(SHA-1, token) - identical to what adbd verifies with
    // RSA_verify(NID_sha1, token, 20, sig, ...).
    fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE) {
            "ADB auth tokens are $TOKEN_SIZE-byte SHA-1 digests, got ${token.size}"
        }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(SHA1_DIGEST_INFO_PREFIX + token)
    }

    // Parses the android_pubkey struct back into (modulus, exponent). Used to re-encode a
    // reference key produced by `adb keygen` and compare byte-for-byte (INST-TEST-029).
    internal fun decodePublicKey(encoded: ByteArray): Pair<BigInteger, BigInteger> {
        require(encoded.size == ENCODED_KEY_SIZE) {
            "android_pubkey blobs are $ENCODED_KEY_SIZE bytes, got ${encoded.size}"
        }
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        val words = buffer.int
        require(words == MODULUS_BITS / 32) { "expected ${MODULUS_BITS / 32} modulus words, got $words" }
        buffer.int // n0inv: derived from the modulus, not independent input
        val modulusLe = ByteArray(MODULUS_BYTES)
        buffer.get(modulusLe)
        buffer.position(buffer.position() + MODULUS_BYTES) // rr: likewise derived
        val exponent = buffer.int
        return fromLittleEndian(modulusLe) to BigInteger.valueOf(exponent.toLong() and 0xFFFFFFFFL)
    }

    private fun toLittleEndian(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(MODULUS_BYTES)
        var written = 0
        var i = bigEndian.size - 1
        while (i >= 0 && written < MODULUS_BYTES) {
            out[written++] = bigEndian[i]
            i--
        }
        while (i >= 0) {
            // Only BigInteger's sign byte may remain; anything else would be silently truncated.
            require(bigEndian[i] == 0.toByte()) { "value does not fit in $MODULUS_BYTES bytes" }
            i--
        }
        return out
    }

    private fun fromLittleEndian(littleEndian: ByteArray): BigInteger {
        val bigEndian = ByteArray(littleEndian.size + 1) // leading zero keeps BigInteger unsigned
        for (i in littleEndian.indices) {
            bigEndian[bigEndian.size - 1 - i] = littleEndian[i]
        }
        return BigInteger(bigEndian)
    }
}
