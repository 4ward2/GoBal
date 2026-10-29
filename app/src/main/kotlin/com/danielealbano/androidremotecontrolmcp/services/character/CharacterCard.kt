package com.danielealbano.androidremotecontrolmcp.services.character

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * A role-play character, as shared by SillyTavern-style "character cards" (the community's
 * Character Card V1/V2/V3 formats). Only the fields the chat uses are kept.
 *
 * @property id stable hash of the defining text, so the same card is not imported twice.
 * @property hasAvatar whether an avatar image was stored next to the card.
 */
@Serializable
data class CharacterCard(
    val id: String,
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val exampleMessages: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val creator: String = "",
    val tags: List<String> = emptyList(),
    val hasAvatar: Boolean = false,
    val importedAt: Long = 0L,
    /** What the character says it is up to right now ("生闷气中"); set by the character itself. */
    val status: String = "",
    /** When the user last picked this character; the roster is a stack ordered by this. */
    val selectedAt: Long = 0L,
    /** Free-form background written by the user on the contact page (cards have no such field). */
    val backstory: String = "",
    /** The provider and model this character talks through; on-device default when blank. */
    val model: ModelChoice = ModelChoice(),
) {
    /** First line of the description with the macros filled in, for lists. */
    val summary: String
        get() =
            description
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.let { CharacterPrompt.fill(it, name, CharacterPrompt.DEFAULT_USER) }
                .orEmpty()
}

/** A card read from a file: the card itself plus the PNG it came in, when it was a PNG. */
data class ParsedCard(
    val card: CharacterCard,
    val avatarPng: ByteArray?,
)

/**
 * Reads character cards from the two ways they travel: a PNG with the card JSON hidden in a text
 * chunk (`chara` = V2, `ccv3` = V3, base64), or a plain JSON file (V1 flat, or V2/V3 with `spec` +
 * `data`). Anything else yields null.
 */
object CharacterCardParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private const val PNG_HEADER_LENGTH = 8
    private const val CHUNK_HEADER_LENGTH = 8
    private const val CHUNK_CRC_LENGTH = 4
    private const val ID_LENGTH = 16
    private const val KEYWORD_V3 = "ccv3"
    private const val KEYWORD_V2 = "chara"
    private const val TEXT_CHUNK = "tEXt"
    private const val END_CHUNK = "IEND"

    fun parse(bytes: ByteArray): ParsedCard? =
        if (isPng(bytes)) {
            embeddedJson(bytes)?.let(::fromJson)?.let { ParsedCard(it, avatarPng = bytes) }
        } else {
            runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()?.let(::fromJson)?.let { ParsedCard(it, null) }
        }

    fun isPng(bytes: ByteArray): Boolean {
        val signed = PNG_SIGNATURE.indices.all { bytes.size > it && bytes[it] == PNG_SIGNATURE[it] }
        return bytes.size > PNG_HEADER_LENGTH && signed
    }

    /** The card JSON inside a PNG's `tEXt` chunks, V3 preferred over V2; null when there is none. */
    fun embeddedJson(png: ByteArray): String? {
        val texts = textChunks(png)
        val encoded = texts[KEYWORD_V3] ?: texts[KEYWORD_V2]
        return encoded?.let { b64 ->
            runCatching { String(Base64.getDecoder().decode(b64.trim()), Charsets.UTF_8) }.getOrNull()
        }
    }

    /** Walks the chunk list (length, type, data, crc) and collects every `tEXt` keyword → text. */
    private fun textChunks(png: ByteArray): Map<String, String> {
        val found = mutableMapOf<String, String>()
        var offset = PNG_HEADER_LENGTH
        var done = false
        while (!done && offset + CHUNK_HEADER_LENGTH <= png.size) {
            val length = ByteBuffer.wrap(png, offset, Int.SIZE_BYTES).int
            val type = String(png, offset + Int.SIZE_BYTES, Int.SIZE_BYTES, Charsets.US_ASCII)
            val dataStart = offset + CHUNK_HEADER_LENGTH
            val intact = length >= 0 && dataStart + length <= png.size
            if (intact && type == TEXT_CHUNK) textChunk(png, dataStart, length)?.let { (k, v) -> found[k] = v }
            done = !intact || type == END_CHUNK
            offset = dataStart + length + CHUNK_CRC_LENGTH
        }
        return found
    }

    /** A `tEXt` chunk is `keyword NUL text`, both Latin-1; base64 is plain ASCII anyway. */
    private fun textChunk(
        png: ByteArray,
        dataStart: Int,
        length: Int,
    ): Pair<String, String>? {
        val separator = (dataStart until dataStart + length).firstOrNull { png[it] == 0.toByte() } ?: return null
        val keyword = String(png, dataStart, separator - dataStart, Charsets.ISO_8859_1)
        val text = String(png, separator + 1, dataStart + length - separator - 1, Charsets.ISO_8859_1)
        return keyword to text
    }

    /** V1 (flat), V2 and V3 (`spec` + `data`) JSON; null when it is not a card or has no name. */
    fun fromJson(text: String): CharacterCard? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val data = root?.let { r -> (r["data"] as? JsonObject)?.takeIf { r["spec"] != null } ?: r }
        val name = data?.text("name")?.trim().orEmpty()
        return data?.takeIf { name.isNotEmpty() }?.let { d ->
            val card =
                CharacterCard(
                    id = "",
                    name = name,
                    description = d.text("description"),
                    personality = d.text("personality"),
                    scenario = d.text("scenario"),
                    firstMessage = d.text("first_mes"),
                    exampleMessages = d.text("mes_example"),
                    systemPrompt = d.text("system_prompt"),
                    postHistoryInstructions = d.text("post_history_instructions"),
                    alternateGreetings = d.strings("alternate_greetings"),
                    creator = d.text("creator"),
                    tags = d.strings("tags"),
                )
            card.copy(id = idFor(card))
        }
    }

    /** Same defining text → same id, whatever file it arrived in. */
    fun idFor(card: CharacterCard): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(card.name, card.description, card.personality, card.scenario, card.firstMessage).forEach {
            digest.update(it.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(ID_LENGTH)
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
