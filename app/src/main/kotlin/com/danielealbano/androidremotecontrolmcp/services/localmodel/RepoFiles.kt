package com.danielealbano.androidremotecontrolmcp.services.localmodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A file in a model repository and its size in bytes. */
data class RepoFile(
    val path: String,
    val bytes: Long,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/** The GGUF files of a repository, split into model weights and vision projectors. */
data class RepoModels(
    val weights: List<RepoFile>,
    val projectors: List<RepoFile>,
)

/** Reads the hubs' file lists and picks the files the phone can use. */
object RepoFiles {
    private val json = Json { ignoreUnknownKeys = true }

    /** Split models ("-00001-of-00003.gguf") are too large for a phone anyway and need every part. */
    private val SHARD = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)

    /** Quantisation tags as they appear in file names, e.g. Q4_K_M, IQ4_XS, Q8_0, BF16. */
    private val QUANT = Regex("(?<![A-Za-z0-9])(I?Q\\d(?:_[A-Z0-9]+)*|BF16|F16|F32)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

    /** What most phones run well, best first: small enough, and hardly worse than the full model. */
    private val PREFERRED = listOf("Q4_K_M", "Q4_0", "Q4_K_S", "IQ4_XS", "IQ4_NL", "Q5_K_M")

    /** Hugging Face: a JSON array of entries; ModelScope: {"Code":200,"Data":{"Files":[...]}}. */
    fun parse(
        hub: ModelHub,
        body: String,
    ): List<RepoFile> {
        val root = json.parseToJsonElement(body)
        val entries: List<JsonElement> =
            when (hub) {
                ModelHub.MODELSCOPE -> {
                    val data = (root as? JsonObject)?.get("Data") as? JsonObject
                    (data?.get("Files") as? JsonArray).orEmpty()
                }

                else -> {
                    (root as? JsonArray).orEmpty()
                }
            }
        return entries.mapNotNull { entryOf(hub, it as? JsonObject) }
    }

    private fun entryOf(
        hub: ModelHub,
        entry: JsonObject?,
    ): RepoFile? {
        val keys = if (hub == ModelHub.MODELSCOPE) MODELSCOPE_KEYS else HUGGING_FACE_KEYS
        val isFile = entry?.get(keys.type)?.jsonPrimitive?.content == keys.fileType
        val path = entry?.get(keys.path)?.jsonPrimitive?.content
        val size = entry?.get(keys.size)?.jsonPrimitive?.longOrNull
        return if (isFile && path != null && size != null) RepoFile(path, size) else null
    }

    private class EntryKeys(
        val type: String,
        val fileType: String,
        val path: String,
        val size: String,
    )

    private val HUGGING_FACE_KEYS = EntryKeys("type", "file", "path", "size")
    private val MODELSCOPE_KEYS = EntryKeys("Type", "blob", "Path", "Size")

    /** GGUF weights and projectors, smallest first; shards and empty files are left out. */
    fun models(files: List<RepoFile>): RepoModels {
        val gguf =
            files
                .filter { it.path.endsWith(".gguf", ignoreCase = true) && it.bytes > 0 && !SHARD.containsMatchIn(it.path) }
                .sortedBy { it.bytes }
        val (projectors, weights) = gguf.partition { isProjector(it) }
        return RepoModels(weights, projectors)
    }

    fun isProjector(file: RepoFile): Boolean = file.fileName.contains("mmproj", ignoreCase = true)

    fun quantOf(path: String): String? =
        QUANT
            .findAll(path.substringAfterLast('/').removeSuffix(".gguf"))
            .lastOrNull()
            ?.value
            ?.uppercase()

    /** The weights to suggest: the first preferred quantisation present, otherwise the smallest. */
    fun preferred(weights: List<RepoFile>): RepoFile? =
        PREFERRED.firstNotNullOfOrNull { q -> weights.firstOrNull { quantOf(it.path) == q } } ?: weights.firstOrNull()

    /** The projector to suggest: 8-bit when there is one (near f16 quality at half the size). */
    fun preferredProjector(projectors: List<RepoFile>): RepoFile? =
        projectors.firstOrNull { quantOf(it.path) == "Q8_0" } ?: projectors.firstOrNull()
}
