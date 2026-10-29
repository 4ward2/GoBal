package com.danielealbano.androidremotecontrolmcp.services.localmodel

import java.net.URI
import java.net.URLEncoder

/** Where model files are downloaded from. Each has its own file-list API and download path. */
enum class ModelHub(
    val label: String,
    val shortLabel: String,
    val hint: String,
    private val host: String,
    /** GLM-Edge 4B chat, the small example model; ModelScope keeps it under another organisation. */
    val exampleRepo: String,
) {
    HUGGING_FACE(
        "Hugging Face",
        "HF",
        "官方站点，国内网络通常连不上",
        "https://huggingface.co",
        "zai-org/glm-edge-4b-chat-gguf",
    ),
    HF_MIRROR(
        "HF 镜像",
        "HF 镜像",
        "hf-mirror.com，国内可以直连，文件与官方一致",
        "https://hf-mirror.com",
        "zai-org/glm-edge-4b-chat-gguf",
    ),
    MODELSCOPE(
        "魔搭 ModelScope",
        "魔搭",
        "阿里的模型社区，国内直连，仓库名可能与 Hugging Face 不同",
        "https://modelscope.cn",
        "ZhipuAI/glm-edge-4b-chat-gguf",
    ),
    ;

    /** The public file list of [repo] ("org/name"), subfolders included. */
    fun listUrl(repo: String): String =
        when (this) {
            MODELSCOPE -> "$host/api/v1/models/$repo/repo/files?Revision=master&Recursive=true"
            else -> "$host/api/models/$repo/tree/main?recursive=true"
        }

    /** Direct download of [path] in [repo]; both hubs redirect to a CDN that honours byte ranges. */
    fun fileUrl(
        repo: String,
        path: String,
    ): String {
        val encoded = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return when (this) {
            MODELSCOPE -> "$host/models/$repo/resolve/master/$encoded"
            else -> "$host/$repo/resolve/main/$encoded"
        }
    }

    val huggingFaceFamily: Boolean get() = this != MODELSCOPE

    companion object {
        fun byName(name: String): ModelHub? = entries.firstOrNull { it.name == name }
    }
}

/** A pasted link, understood: the repository, and the file when the link pointed at one. */
data class ModelLink(
    val hub: ModelHub,
    val repo: String,
    val file: String? = null,
)

/**
 * Reads what people paste: a repository page, a file page, a download link, or just "org/name".
 * Hugging Face and its mirror hold the same repositories, so a link from either keeps the hub the
 * user chose among the two; a ModelScope link means ModelScope (and the other way round), since
 * repository names differ between the two.
 */
object ModelLinks {
    private val REPO_PART = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val BARE = Regex("^(${REPO_PART.pattern})/(${REPO_PART.pattern})$")

    /** Words after the repository that start the file part of a link, per site. */
    private val FILE_MARKERS = setOf("blob", "resolve", "tree", "view", "file", "files")
    private val REVISIONS = setOf("main", "master")

    fun parse(
        text: String,
        chosen: ModelHub,
    ): ModelLink? {
        val trimmed = text.trim().trimEnd('/')
        BARE.matchEntire(trimmed)?.let { return ModelLink(chosen, "${it.groupValues[1]}/${it.groupValues[2]}") }
        val url = Regex("https?://\\S+").find(trimmed)?.value ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val segments =
            uri.path
                .orEmpty()
                .split('/')
                .filter { it.isNotEmpty() }
        return when {
            host == "modelscope.cn" -> {
                fromSegments(ModelHub.MODELSCOPE, segments.dropWhile { it == "models" })
            }

            host == "huggingface.co" || host == "hf-mirror.com" -> {
                val hub = if (chosen.huggingFaceFamily) chosen else hubOfHost(host)
                fromSegments(hub, segments.dropWhile { it == "models" })
            }

            else -> {
                null
            }
        }
    }

    private fun hubOfHost(host: String): ModelHub = if (host == "hf-mirror.com") ModelHub.HF_MIRROR else ModelHub.HUGGING_FACE

    private fun fromSegments(
        hub: ModelHub,
        segments: List<String>,
    ): ModelLink? {
        if (segments.size < 2) return null
        val (org, name) = segments
        if (!REPO_PART.matches(org) || !REPO_PART.matches(name)) return null
        val rest = segments.drop(2).dropWhile { it in FILE_MARKERS || it in REVISIONS }
        val file = rest.joinToString("/").takeIf { it.endsWith(".gguf", ignoreCase = true) }
        return ModelLink(hub, "$org/$name", file)
    }
}
