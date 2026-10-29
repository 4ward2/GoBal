package com.danielealbano.androidremotecontrolmcp.services.localmodel

/** Turns a file picked from a repository into a [LocalModelSpec]: name, context and memory. */
object LocalModelPlan {
    /**
     * The server reserves the key/value cache for the whole context up front, so a model trained
     * on 128K tokens would claim gigabytes it never uses in a chat. Capped here.
     */
    const val MAX_CONTEXT = 8192

    /** When the file's header could not be read. */
    const val UNKNOWN_CONTEXT = 4096

    private const val MIB = 1024L * 1024

    /** Compute buffers, the server itself and some slack. */
    private const val HEADROOM = 768 * MIB

    /** A key/value cache guess for models whose shape is unknown. */
    private const val UNKNOWN_KV = 512 * MIB

    private val REPO_SUFFIX = Regex("[-_.]gguf$", RegexOption.IGNORE_CASE)
    private val NOT_ID = Regex("[^a-z0-9._-]+")

    fun contextFor(info: GgufInfo?): Int {
        val trained = info?.contextLength?.takeIf { it > 0 } ?: UNKNOWN_CONTEXT
        return trained.coerceAtMost(MAX_CONTEXT)
    }

    fun ramFor(
        downloadBytes: Long,
        info: GgufInfo?,
        context: Int,
    ): Long = downloadBytes + (info?.kvCacheBytes(context) ?: UNKNOWN_KV) + HEADROOM

    /** "Qwen3-4B · Q4_K_M": the repository without its "-GGUF" and the quantisation. */
    fun nameOf(
        repo: String,
        file: RepoFile,
    ): String {
        val base = repo.substringAfter('/').replace(REPO_SUFFIX, "")
        val variant = RepoFiles.quantOf(file.path) ?: file.fileName.removeSuffix(".gguf")
        return "$base · $variant"
    }

    fun idOf(name: String): String =
        name
            .lowercase()
            .replace(" · ", "-")
            .replace(NOT_ID, "-")
            .trim('-')

    fun spec(
        hub: ModelHub,
        repo: String,
        weights: RepoFile,
        projector: RepoFile?,
        info: GgufInfo?,
    ): LocalModelSpec {
        val name = nameOf(repo, weights)
        val context = contextFor(info)
        val files =
            listOfNotNull(weights, projector).map { file ->
                LocalModelFile(file.fileName, hub.fileUrl(repo, file.path), file.bytes)
            }
        return LocalModelSpec(
            id = idOf(name),
            name = name,
            weights = files.first(),
            mmproj = files.getOrNull(1),
            contextSize = context,
            ramBytes = ramFor(files.sumOf { it.bytes }, info, context),
            hub = hub.name,
            repo = repo,
        )
    }
}
