/*
 * The adb transport and pairing packets, as spoken to this phone's own wireless debugging.
 *
 * Follows the protocol as implemented in Shizuku (manager/src/main/java/moe/shizuku/manager/adb),
 * Copyright (c) RikkaApps, licensed under the Apache License, Version 2.0; rewritten for this app.
 */
package com.danielealbano.androidremotecontrolmcp.services.shell

import java.io.DataInputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Command words and constants of the adb transport. */
internal object AdbWire {
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257
    const val STLS = 0x534c5453

    const val VERSION = 0x01000000
    const val STLS_VERSION = 0x01000000
    const val MAX_DATA = 4096
    const val HEADER = 24

    /** No honest packet is larger; a length beyond it means the stream is not adb. */
    const val MAX_PAYLOAD = 1 shl 20
}

/** One adb packet: a 24-byte little-endian header and its payload. */
internal class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray = EMPTY,
) {
    fun encode(): ByteArray =
        ByteBuffer
            .allocate(AdbWire.HEADER + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(data.size)
            .putInt(checksum(data))
            .putInt(command.inv())
            .put(data)
            .array()

    companion object {
        private val EMPTY = ByteArray(0)

        fun text(
            command: Int,
            arg0: Int,
            arg1: Int,
            text: String,
        ): AdbPacket = AdbPacket(command, arg0, arg1, (text + "\u0000").toByteArray())

        /** adb's "crc": the plain sum of the payload's bytes. */
        fun checksum(data: ByteArray): Int = data.sumOf { it.toInt() and BYTE }

        /** Reads one packet, refusing anything that is not well-formed adb. */
        @Throws(IOException::class)
        fun read(input: DataInputStream): AdbPacket {
            val head = ByteArray(AdbWire.HEADER)
            input.readFully(head)
            val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val length = buffer.int
            val sum = buffer.int
            val magic = buffer.int
            if (magic != command.inv() || length !in 0..AdbWire.MAX_PAYLOAD) throw IOException("not an adb packet")
            val data = ByteArray(length)
            input.readFully(data)
            if (length > 0 && sum != 0 && sum != checksum(data)) throw IOException("adb packet checksum")
            return AdbPacket(command, arg0, arg1, data)
        }

        private const val BYTE = 0xff
    }
}

/** adb's own encoding of an RSA public key (`android_pubkey`), as sent when pairing. */
internal object AdbPublicKey {
    private const val MODULUS_BYTES = 256
    private const val WORDS = MODULUS_BYTES / 4
    private const val WORD_BITS = 32
    private const val ENCODED = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

    /** `base64(struct) + " " + name + NUL`. */
    fun encode(
        modulus: BigInteger,
        exponent: BigInteger,
        name: String,
    ): ByteArray {
        val r32 = BigInteger.ONE.shiftLeft(WORD_BITS)
        val n0inv = modulus.mod(r32).modInverse(r32).negate()
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * Byte.SIZE_BITS).modPow(BigInteger.valueOf(2), modulus)
        val buffer = ByteBuffer.allocate(ENCODED).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(WORDS)
        buffer.putInt(n0inv.toInt())
        words(modulus, r32).forEach { buffer.putInt(it) }
        words(rr, r32).forEach { buffer.putInt(it) }
        buffer.putInt(exponent.toInt())
        return Base64.getEncoder().encode(buffer.array()) + " $name\u0000".toByteArray()
    }

    /** Little-endian 32-bit words, zero-padded at the high end. */
    private fun words(
        value: BigInteger,
        r32: BigInteger,
    ): IntArray {
        var rest = value
        return IntArray(WORDS) {
            val parts = rest.divideAndRemainder(r32)
            rest = parts[0]
            parts[1].toInt()
        }
    }
}

/** The framing of the pairing conversation (big-endian, unlike the transport). */
internal object PairingPackets {
    const val SPAKE2_MSG: Byte = 0
    const val PEER_INFO: Byte = 1
    const val HEADER = 6
    const val PEER_INFO_SIZE = 8192

    private const val VERSION: Byte = 1
    private const val RSA_PUB_KEY: Byte = 0
    private const val MAX_PAYLOAD = PEER_INFO_SIZE * 2

    fun header(
        type: Byte,
        payload: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER)
            .put(VERSION)
            .put(type)
            .putInt(payload)
            .array()

    /** The payload size the header announces, or null when it is not a header we accept for [type]. */
    fun payloadOf(
        header: ByteArray,
        type: Byte,
    ): Int? {
        if (header.size != HEADER) return null
        val buffer = ByteBuffer.wrap(header)
        val accepted = buffer.get() == VERSION && buffer.get() == type
        val payload = buffer.int
        return payload.takeIf { accepted && it in 1..MAX_PAYLOAD }
    }

    /** Our side's peer info: the key type, then the key, zero-padded to the fixed size. */
    fun peerInfo(adbPublicKey: ByteArray): ByteArray {
        require(adbPublicKey.size < PEER_INFO_SIZE) { "public key too long" }
        return ByteArray(PEER_INFO_SIZE).also {
            it[0] = RSA_PUB_KEY
            adbPublicKey.copyInto(it, 1)
        }
    }
}
