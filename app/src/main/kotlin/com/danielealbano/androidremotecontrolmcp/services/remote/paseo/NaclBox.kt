package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * NaCl `box` (Curve25519 + XSalsa20-Poly1305), the cipher a Paseo daemon speaks through the
 * relay. Built on the primitives the app already ships; checked against libsodium's own output
 * in the tests, byte for byte.
 */
object NaclBox {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val TAG_BYTES = 16
    private val random = SecureRandom()

    class KeyPair(
        val public: ByteArray,
        val secret: ByteArray,
    )

    fun keyPair(): KeyPair {
        val secret = ByteArray(KEY_BYTES).also(random::nextBytes)
        return KeyPair(publicKey(secret), secret)
    }

    fun publicKey(secret: ByteArray): ByteArray {
        val public = ByteArray(KEY_BYTES)
        X25519.scalarMultBase(secret, 0, public, 0)
        return public
    }

    /**
     * The key both ends share (`crypto_box_beforenm`).
     * @throws IllegalArgumentException for a peer key that yields no secret at all.
     */
    fun sharedKey(
        secret: ByteArray,
        peerPublic: ByteArray,
    ): ByteArray {
        require(secret.size == KEY_BYTES && peerPublic.size == KEY_BYTES) { "keys are 32 bytes" }
        val point = ByteArray(KEY_BYTES)
        X25519.scalarMult(secret, 0, peerPublic, 0, point, 0)
        require(point.any { it != 0.toByte() }) { "invalid peer public key" }
        return Salsa.hsalsa20(point, ByteArray(Salsa.HNONCE_BYTES))
    }

    /** Ciphertext with its 16-byte tag in front (`crypto_box_afternm`). */
    fun seal(
        shared: ByteArray,
        nonce: ByteArray,
        plain: ByteArray,
    ): ByteArray {
        val stream = Salsa.xsalsa20(shared, nonce, KEY_BYTES + plain.size)
        val body = ByteArray(plain.size) { (plain[it].toInt() xor stream[KEY_BYTES + it].toInt()).toByte() }
        return tag(stream, body) + body
    }

    /** The plaintext, or null when the box was not made with this key or was altered. */
    fun open(
        shared: ByteArray,
        nonce: ByteArray,
        box: ByteArray,
    ): ByteArray? {
        if (box.size < TAG_BYTES) return null
        val body = box.copyOfRange(TAG_BYTES, box.size)
        val stream = Salsa.xsalsa20(shared, nonce, KEY_BYTES + body.size)
        val genuine = MessageDigest.isEqual(tag(stream, body), box.copyOfRange(0, TAG_BYTES))
        val plain = ByteArray(body.size) { (body[it].toInt() xor stream[KEY_BYTES + it].toInt()).toByte() }
        return plain.takeIf { genuine }
    }

    /** A frame as it goes over the relay: base64 of a fresh nonce followed by the box. */
    fun sealFrame(
        shared: ByteArray,
        text: String,
    ): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return Base64.getEncoder().encodeToString(nonce + seal(shared, nonce, text.toByteArray()))
    }

    /** The text inside a relay frame, or null when it is not ours to read. */
    fun openFrame(
        shared: ByteArray,
        frame: String,
    ): String? {
        val bytes = runCatching { Base64.getDecoder().decode(frame.trim()) }.getOrNull()
        return bytes?.let(::openBytes)?.let { open(shared, it.first, it.second) }?.toString(Charsets.UTF_8)
    }

    private fun openBytes(bundle: ByteArray): Pair<ByteArray, ByteArray>? =
        bundle.takeIf { it.size >= NONCE_BYTES + TAG_BYTES }?.let {
            it.copyOfRange(0, NONCE_BYTES) to it.copyOfRange(NONCE_BYTES, it.size)
        }

    /** Poly1305 over the ciphertext, keyed by the first 32 bytes of the stream. */
    private fun tag(
        stream: ByteArray,
        body: ByteArray,
    ): ByteArray {
        val mac = Poly1305()
        mac.init(KeyParameter(stream, 0, KEY_BYTES))
        mac.update(body, 0, body.size)
        return ByteArray(TAG_BYTES).also { mac.doFinal(it, 0) }
    }
}

/** The four state words one quarter round mixes, in the order Salsa20 names them. */
private class Quarter(
    val a: Int,
    val b: Int,
    val c: Int,
    val d: Int,
)

/** Salsa20 as NaCl uses it: the HSalsa20 key derivation and the XSalsa20 key stream. */
internal class Salsa private constructor() {
    companion object {
        const val HNONCE_BYTES = 16
        private const val BLOCK_BYTES = 64
        private const val WORDS = 16
        private const val DOUBLE_ROUNDS = 10
        private const val WORD_BYTES = 4
        private const val BYTE_MASK = 0xff
        private const val ROTATE_A = 7
        private const val ROTATE_B = 9
        private const val ROTATE_C = 13
        private const val ROTATE_D = 18
        private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

        // Where the constants, the key halves and the 16 nonce/counter bytes sit in the 4x4 state.
        private val SIGMA_AT = intArrayOf(0, 5, 10, 15)
        private val KEY_LOW_AT = intArrayOf(1, 2, 3, 4)
        private val KEY_HIGH_AT = intArrayOf(11, 12, 13, 14)
        private val INPUT_AT = intArrayOf(6, 7, 8, 9)
        private val HSALSA_OUT = SIGMA_AT + INPUT_AT

        // The words each quarter round mixes; the four of the column round, then of the row round.
        private val QUARTERS =
            listOf(
                Quarter(0, 4, 8, 12),
                Quarter(5, 9, 13, 1),
                Quarter(10, 14, 2, 6),
                Quarter(15, 3, 7, 11),
                Quarter(0, 1, 2, 3),
                Quarter(5, 6, 7, 4),
                Quarter(10, 11, 8, 9),
                Quarter(15, 12, 13, 14),
            )

        fun hsalsa20(
            key: ByteArray,
            input: ByteArray,
        ): ByteArray {
            val mixed = rounds(state(key, input))
            val out = ByteArray(NaclBox.KEY_BYTES)
            HSALSA_OUT.forEachIndexed { i, at -> putWord(out, i, mixed[at]) }
            return out
        }

        /** [length] bytes of the XSalsa20 stream for a 24-byte [nonce]. */
        fun xsalsa20(
            key: ByteArray,
            nonce: ByteArray,
            length: Int,
        ): ByteArray {
            require(nonce.size == NaclBox.NONCE_BYTES) { "nonce is 24 bytes" }
            val subKey = hsalsa20(key, nonce.copyOfRange(0, HNONCE_BYTES))
            val out = ByteArray(length)
            var block = 0L
            while (block * BLOCK_BYTES < length) {
                val input = nonce.copyOfRange(HNONCE_BYTES, NaclBox.NONCE_BYTES) + counter(block)
                val start = state(subKey, input)
                val mixed = rounds(start)
                val bytes = ByteArray(BLOCK_BYTES).also { b -> repeat(WORDS) { putWord(b, it, mixed[it] + start[it]) } }
                val offset = (block * BLOCK_BYTES).toInt()
                bytes.copyInto(out, offset, 0, minOf(BLOCK_BYTES, length - offset))
                block++
            }
            return out
        }

        private fun counter(block: Long): ByteArray {
            val shifts = (0 until Long.SIZE_BYTES).map { it * Byte.SIZE_BITS }
            return ByteArray(Long.SIZE_BYTES) { (block ushr shifts[it]).toByte() }
        }

        private fun state(
            key: ByteArray,
            input: ByteArray,
        ): IntArray {
            val state = IntArray(WORDS)
            repeat(WORD_BYTES) { i ->
                state[SIGMA_AT[i]] = SIGMA[i]
                state[KEY_LOW_AT[i]] = word(key, i)
                state[KEY_HIGH_AT[i]] = word(key, i + WORD_BYTES)
                state[INPUT_AT[i]] = word(input, i)
            }
            return state
        }

        private fun rounds(start: IntArray): IntArray {
            val x = start.copyOf()
            repeat(DOUBLE_ROUNDS) {
                QUARTERS.forEach { q ->
                    x[q.b] = x[q.b] xor (x[q.a] + x[q.d]).rotateLeft(ROTATE_A)
                    x[q.c] = x[q.c] xor (x[q.b] + x[q.a]).rotateLeft(ROTATE_B)
                    x[q.d] = x[q.d] xor (x[q.c] + x[q.b]).rotateLeft(ROTATE_C)
                    x[q.a] = x[q.a] xor (x[q.d] + x[q.c]).rotateLeft(ROTATE_D)
                }
            }
            return x
        }

        private fun word(
            bytes: ByteArray,
            index: Int,
        ): Int {
            val at = index * WORD_BYTES
            return (0 until WORD_BYTES).fold(0) { acc, i ->
                acc or ((bytes[at + i].toInt() and BYTE_MASK) shl (i * Byte.SIZE_BITS))
            }
        }

        private fun putWord(
            bytes: ByteArray,
            index: Int,
            value: Int,
        ) {
            repeat(WORD_BYTES) { bytes[index * WORD_BYTES + it] = (value ushr (it * Byte.SIZE_BITS)).toByte() }
        }
    }
}
