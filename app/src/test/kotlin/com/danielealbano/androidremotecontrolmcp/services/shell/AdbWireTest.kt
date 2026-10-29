package com.danielealbano.androidremotecontrolmcp.services.shell

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AdbWireTest {
    private fun read(bytes: ByteArray) = AdbPacket.read(DataInputStream(ByteArrayInputStream(bytes)))

    @Test
    fun `a packet survives being written and read back`() {
        val sent = AdbPacket.text(AdbWire.OPEN, 1, 0, "shell:id")
        val back = read(sent.encode())
        assertEquals(AdbWire.OPEN, back.command)
        assertEquals(1, back.arg0)
        assertArrayEquals("shell:id\u0000".toByteArray(), back.data)
    }

    @Test
    fun `the connect packet is byte for byte what adb sends`() {
        val bytes = AdbPacket.text(AdbWire.CNXN, AdbWire.VERSION, AdbWire.MAX_DATA, "host::").encode()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        // CNXN, version 0x01000000, maxdata 4096, length 7, sum of "host::\0", magic, payload
        val header = "434e584e" + "00000001" + "00100000" + "07000000" + "32020000" + "bcb1a7b1"
        assertEquals(header + "686f73743a3a00", hex)
    }

    @Test
    fun `bytes that are not adb are refused`() {
        val wrongMagic = AdbPacket(AdbWire.OKAY, 1, 2).encode().also { it[20] = 0 }
        assertThrows<IOException> { read(wrongMagic) }
        val huge = ByteBuffer.allocate(AdbWire.HEADER).order(ByteOrder.LITTLE_ENDIAN)
        huge
            .putInt(AdbWire.WRTE)
            .putInt(0)
            .putInt(0)
            .putInt(Int.MAX_VALUE)
            .putInt(0)
            .putInt(AdbWire.WRTE.inv())
        assertThrows<IOException> { read(huge.array()) }
        val badSum = AdbPacket(AdbWire.WRTE, 1, 2, byteArrayOf(1, 2, 3)).encode().also { it[16] = 9 }
        assertThrows<IOException> { read(badSum) }
    }

    @Test
    fun `the public key is in adb's own format`() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val key = generator.generateKeyPair().public as RSAPublicKey
        val encoded = String(AdbPublicKey.encode(key.modulus, key.publicExponent, "brooce@phone"))
        assertTrue(encoded.endsWith(" brooce@phone\u0000"))
        val decoded = Base64.getDecoder().decode(encoded.substringBefore(' '))
        val struct = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(524, struct.capacity())
        assertEquals(64, struct.int)
        val n0inv = struct.int.toLong() and 0xffffffffL
        val littleEndian = ByteArray(256).also { struct.get(it) }
        assertEquals(key.modulus, BigInteger(1, littleEndian.reversedArray()))
        // n0inv is -1/n[0] mod 2^32: multiplying back by the lowest word gives -1.
        val low = key.modulus.toLong() and 0xffffffffL
        assertEquals(0xffffffffL, (n0inv * low) and 0xffffffffL)
        struct.position(struct.capacity() - 4)
        assertEquals(65537, struct.int)
    }

    @Test
    fun `pairing headers are read only when they are what was asked for`() {
        val header = PairingPackets.header(PairingPackets.PEER_INFO, 8208)
        assertEquals(8208, PairingPackets.payloadOf(header, PairingPackets.PEER_INFO))
        assertNull(PairingPackets.payloadOf(header, PairingPackets.SPAKE2_MSG))
        val empty = PairingPackets.header(PairingPackets.PEER_INFO, 0)
        assertNull(PairingPackets.payloadOf(empty, PairingPackets.PEER_INFO))
        assertNull(PairingPackets.payloadOf(header.copyOf(5), PairingPackets.PEER_INFO))
        assertNull(PairingPackets.payloadOf(header.also { it[0] = 2 }, PairingPackets.PEER_INFO))
    }

    @Test
    fun `peer info is the key type, the key, and padding to the fixed size`() {
        val info = PairingPackets.peerInfo(byteArrayOf(7, 8, 9))
        assertEquals(8192, info.size)
        assertArrayEquals(byteArrayOf(0, 7, 8, 9, 0), info.copyOf(5))
        assertFalse(info.drop(4).any { it != 0.toByte() })
    }
}
