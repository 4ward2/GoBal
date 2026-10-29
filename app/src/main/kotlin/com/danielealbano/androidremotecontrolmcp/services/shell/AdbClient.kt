package com.danielealbano.androidremotecontrolmcp.services.shell

import org.conscrypt.Conscrypt
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/** The native half of pairing (`libadbpairing.so`, see `app/src/main/cpp/adb_pairing.cpp`). */
internal object PairingNative {
    init {
        System.loadLibrary("adbpairing")
    }

    @JvmStatic
    external fun create(password: ByteArray): Long

    @JvmStatic
    external fun message(handle: Long): ByteArray

    @JvmStatic
    external fun initCipher(
        handle: Long,
        theirMessage: ByteArray,
    ): Boolean

    @JvmStatic
    external fun encrypt(
        handle: Long,
        plain: ByteArray,
    ): ByteArray?

    @JvmStatic
    external fun decrypt(
        handle: Long,
        sealed: ByteArray,
    ): ByteArray?

    @JvmStatic
    external fun destroy(handle: Long)
}

/** How one pairing attempt ended. */
enum class PairOutcome {
    PAIRED,

    /** The phone could not read what we sealed with the code: the code was wrong. */
    WRONG_CODE,

    /** The pairing page was gone, or the conversation broke. */
    FAILED,
}

/**
 * Pairs this app's key with the phone's wireless debugging, on the loopback address, with the
 * six digits the phone is showing. The code itself never leaves this process unencrypted: both
 * sides prove knowledge of it through SPAKE2, bound to the TLS session.
 */
internal class AdbPairingClient(
    private val port: Int,
    private val code: String,
    private val identity: AdbIdentity,
) {
    fun pair(): PairOutcome = runCatching { converse() }.getOrDefault(PairOutcome.FAILED)

    private fun converse(): PairOutcome {
        Loopback.tls(port, identity).use { socket ->
            val export = Conscrypt.exportKeyingMaterial(socket, EXPORT_LABEL, null, EXPORT_SIZE)
            val handle = PairingNative.create(code.toByteArray() + export)
            if (handle == 0L) return PairOutcome.FAILED
            try {
                return exchange(handle, DataInputStream(socket.inputStream), socket.outputStream)
            } finally {
                PairingNative.destroy(handle)
            }
        }
    }

    private fun exchange(
        handle: Long,
        input: DataInputStream,
        output: OutputStream,
    ): PairOutcome {
        send(output, PairingPackets.SPAKE2_MSG, PairingNative.message(handle))
        val theirs = receive(input, PairingPackets.SPAKE2_MSG)
        if (theirs == null || !PairingNative.initCipher(handle, theirs)) return PairOutcome.FAILED

        val sealed = PairingNative.encrypt(handle, PairingPackets.peerInfo(identity.adbPublicKey))
        val answer =
            sealed?.let {
                send(output, PairingPackets.PEER_INFO, it)
                // A phone that could not open our packet (wrong code) hangs up here instead of answering.
                runCatching { receive(input, PairingPackets.PEER_INFO) }.getOrNull()
            }
        val opened = answer?.let { PairingNative.decrypt(handle, it) }
        return if (opened?.size == PairingPackets.PEER_INFO_SIZE) PairOutcome.PAIRED else PairOutcome.WRONG_CODE
    }

    private fun send(
        output: OutputStream,
        type: Byte,
        payload: ByteArray,
    ) {
        output.write(PairingPackets.header(type, payload.size))
        output.write(payload)
        output.flush()
    }

    private fun receive(
        input: DataInputStream,
        type: Byte,
    ): ByteArray? {
        val header = ByteArray(PairingPackets.HEADER).also { input.readFully(it) }
        val size = PairingPackets.payloadOf(header, type) ?: return null
        return ByteArray(size).also { input.readFully(it) }
    }

    private companion object {
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_SIZE = 64
    }
}

/** Sockets to this phone's own adbd. */
internal object Loopback {
    const val HOST = "127.0.0.1"
    private const val CONNECT_MS = 5_000
    private const val READ_MS = 20_000

    fun plain(port: Int): Socket =
        Socket().apply {
            tcpNoDelay = true
            soTimeout = READ_MS
            connect(InetSocketAddress(HOST, port), CONNECT_MS)
        }

    fun secure(
        socket: Socket,
        port: Int,
        identity: AdbIdentity,
    ): SSLSocket {
        val secure = identity.sslContext.socketFactory.createSocket(socket, HOST, port, true) as SSLSocket
        secure.startHandshake()
        return secure
    }

    fun tls(
        port: Int,
        identity: AdbIdentity,
    ): SSLSocket {
        val socket = plain(port)
        return runCatching { secure(socket, port, identity) }.getOrElse {
            socket.close()
            throw it
        }
    }
}

/** adbd refused this app's key: it has to be paired (again). */
class NotPairedException : IOException("wireless debugging does not know this app's key")

/**
 * One connection to the phone's wireless debugging, good for running commands as the shell user.
 * Only the TLS flavour is spoken: that is the only one wireless debugging offers.
 */
internal class AdbConnection(
    private val port: Int,
    private val identity: AdbIdentity,
) : Closeable {
    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var nextLocalId = 1

    @Throws(IOException::class)
    fun connect() {
        val plain = Loopback.plain(port)
        socket = plain
        input = DataInputStream(plain.getInputStream())
        output = plain.getOutputStream()
        write(AdbPacket.text(AdbWire.CNXN, AdbWire.VERSION, AdbWire.MAX_DATA, "host::"))
        if (AdbPacket.read(input).command != AdbWire.STLS) throw IOException("adbd did not offer TLS")
        write(AdbPacket(AdbWire.STLS, AdbWire.STLS_VERSION, 0))

        // adbd checks the client certificate after the handshake; an unknown key shows as a dead stream.
        val greeting = runCatching { greetingOverTls(plain) }.getOrNull()
        if (greeting?.command != AdbWire.CNXN) throw NotPairedException()
    }

    private fun greetingOverTls(plain: Socket): AdbPacket {
        val secure = Loopback.secure(plain, port, identity)
        socket = secure
        input = DataInputStream(secure.inputStream)
        output = secure.outputStream
        return AdbPacket.read(input)
    }

    /** Runs [command] as the shell user and returns what it printed (stdout and stderr together). */
    @Throws(IOException::class)
    fun shell(command: String): String {
        val local = nextLocalId++
        write(AdbPacket.text(AdbWire.OPEN, local, 0, "shell:$command"))
        val printed = ByteArrayOutputStream()
        while (true) {
            val packet = AdbPacket.read(input)
            when (packet.command) {
                AdbWire.OKAY -> {
                    Unit
                }

                AdbWire.WRTE -> {
                    if (printed.size() + packet.data.size > MAX_OUTPUT) throw IOException("too much output")
                    printed.write(packet.data)
                    write(AdbPacket(AdbWire.OKAY, local, packet.arg0))
                }

                AdbWire.CLSE -> {
                    write(AdbPacket(AdbWire.CLSE, local, packet.arg0))
                    return String(printed.toByteArray(), Charsets.UTF_8)
                }

                else -> {
                    throw IOException("unexpected adb packet")
                }
            }
        }
    }

    private fun write(packet: AdbPacket) {
        output.write(packet.encode())
        output.flush()
    }

    override fun close() {
        runCatching { socket?.close() }
    }

    private companion object {
        const val MAX_OUTPUT = 4 shl 20
    }
}
