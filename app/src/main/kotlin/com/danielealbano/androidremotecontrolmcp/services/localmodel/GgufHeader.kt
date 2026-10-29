package com.danielealbano.androidremotecontrolmcp.services.localmodel

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the start of a GGUF file says about the model: enough to size the context and the memory
 * it takes. Fields the file does not carry are null.
 */
data class GgufInfo(
    val architecture: String,
    val contextLength: Int?,
    val blocks: Int?,
    val heads: Int?,
    val headsKv: Int?,
    val embedding: Int?,
    val keyLength: Int?,
    val valueLength: Int?,
) {
    /** Bytes of the f16 key/value cache for [context] tokens, or null when the shape is unknown. */
    fun kvCacheBytes(context: Int): Long? {
        val layers = blocks ?: return null
        val kvHeads = headsKv ?: heads ?: return null
        val headDim = heads?.let { h -> embedding?.div(h) }
        val k = keyLength ?: headDim ?: return null
        val v = valueLength ?: headDim ?: return null
        return layers.toLong() * context * kvHeads * (k + v) * F16_BYTES
    }

    private companion object {
        const val F16_BYTES = 2L
    }
}

/**
 * Reads the key/value metadata at the start of a GGUF file (format versions 2 and 3). Only the
 * first part of the file is fetched: the model's shape comes right after the general keys, and
 * reading stops at the tokenizer, whose vocabulary can run to megabytes.
 */
object GgufHeader {
    /** Bytes to fetch; the keys needed sit in the first few kilobytes of every file seen so far. */
    const val PREFIX_BYTES = 256 * 1024

    private const val MAGIC = 0x46554747 // "GGUF", little-endian
    private const val MIN_VERSION = 2

    private const val T_UINT8 = 0
    private const val T_INT8 = 1
    private const val T_UINT16 = 2
    private const val T_INT16 = 3
    private const val T_UINT32 = 4
    private const val T_INT32 = 5
    private const val T_FLOAT32 = 6
    private const val T_BOOL = 7
    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private const val T_UINT64 = 10
    private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    private val SIZES =
        mapOf(
            T_UINT8 to 1,
            T_INT8 to 1,
            T_BOOL to 1,
            T_UINT16 to 2,
            T_INT16 to 2,
            T_UINT32 to 4,
            T_INT32 to 4,
            T_FLOAT32 to 4,
            T_UINT64 to 8,
            T_INT64 to 8,
            T_FLOAT64 to 8,
        )

    /** Parses [prefix]; null when it is not a GGUF file or names no architecture. */
    fun read(prefix: ByteArray): GgufInfo? {
        val buf = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN)
        val keys = runCatching { readKeys(buf) }.getOrNull()
        val arch = keys?.get("general.architecture") as? String ?: return null

        fun int(name: String): Int? = (keys?.get("$arch.$name") as? Number)?.toInt()
        return GgufInfo(
            architecture = arch,
            contextLength = int("context_length"),
            blocks = int("block_count"),
            heads = int("attention.head_count"),
            headsKv = int("attention.head_count_kv"),
            embedding = int("embedding_length"),
            keyLength = int("attention.key_length"),
            valueLength = int("attention.value_length"),
        )
    }

    /** Scalar keys until the tokenizer or the end of [buf]; a cut-off key simply ends the list. */
    private fun readKeys(buf: ByteBuffer): Map<String, Any>? {
        if (buf.int != MAGIC || buf.int < MIN_VERSION) return null
        buf.long // tensor count
        val count = buf.long
        val keys = mutableMapOf<String, Any>()
        try {
            var i = 0L
            var more = true
            while (more && i < count) {
                val key = string(buf)
                more = !key.startsWith("tokenizer.")
                if (more) value(buf, buf.int)?.let { keys[key] = it }
                i++
            }
        } catch (_: BufferUnderflowException) {
            // The prefix ended inside a key: everything before it is still good.
        }
        return keys
    }

    private fun string(buf: ByteBuffer): String {
        val length = buf.long
        if (length < 0 || length > buf.remaining()) throw BufferUnderflowException()
        val bytes = ByteArray(length.toInt())
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Reads one value; arrays are skipped (none of the keys needed is an array). */
    private fun value(
        buf: ByteBuffer,
        type: Int,
    ): Any? =
        when (type) {
            T_UINT8, T_INT8 -> buf.get().toInt()
            T_BOOL -> buf.get().toInt() != 0
            T_UINT16, T_INT16 -> buf.short.toInt()
            T_UINT32 -> buf.int.toLong() and UINT32_MASK
            T_INT32 -> buf.int
            T_FLOAT32 -> buf.float
            T_UINT64, T_INT64 -> buf.long
            T_FLOAT64 -> buf.double
            T_STRING -> string(buf)
            T_ARRAY -> skipArray(buf)
            else -> throw BufferUnderflowException()
        }

    private fun skipArray(buf: ByteBuffer): Any? {
        val type = buf.int
        val count = buf.long
        val size = SIZES[type]
        if (size != null) {
            val bytes = count * size
            if (bytes < 0 || bytes > buf.remaining()) throw BufferUnderflowException()
            buf.position(buf.position() + bytes.toInt())
        } else {
            repeat(count.toInt()) { value(buf, type) }
        }
        return null
    }

    private const val UINT32_MASK = 0xFFFFFFFFL
}
