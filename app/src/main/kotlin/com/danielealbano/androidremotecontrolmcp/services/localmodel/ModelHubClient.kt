package com.danielealbano.androidremotecontrolmcp.services.localmodel

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** The two requests the hubs are asked: a JSON file list, and the first bytes of a file. */
interface HubHttp {
    /** Status code and body of a GET. */
    fun text(url: String): Pair<Int, String>

    /** Status code and at most [bytes] bytes from the start of [url] (following redirects). */
    fun prefix(
        url: String,
        bytes: Int,
    ): Pair<Int, ByteArray>
}

/**
 * Lists a repository's GGUF files and reads a file's header. Nothing here sends credentials:
 * only public repositories can be listed, and a model that needs a login fails at download
 * with a message saying so.
 */
@Singleton
class ModelHubClient
    @Inject
    constructor() {
        var http: HubHttp = UrlConnectionHubHttp()

        fun list(link: ModelLink): Result<RepoModels> =
            runCatching {
                val (code, body) =
                    try {
                        http.text(link.hub.listUrl(link.repo))
                    } catch (e: IOException) {
                        throw IOException(unreachable(link.hub), e)
                    }
                check(code in HTTP_OK_RANGE) { listError(link, code) }
                val models = RepoFiles.models(RepoFiles.parse(link.hub, body))
                check(models.weights.isNotEmpty()) { "这个仓库里没有 GGUF 模型文件。手机上只能运行 GGUF 格式，找名字带 GGUF 的仓库。" }
                models
            }

        /** The header of the file at [url]; null when it cannot be fetched or read. */
        fun header(url: String): GgufInfo? =
            runCatching {
                val (code, bytes) = http.prefix(url, GgufHeader.PREFIX_BYTES)
                if (code in HTTP_OK_RANGE) GgufHeader.read(bytes) else null
            }.getOrNull()

        private fun unreachable(hub: ModelHub): String =
            if (hub == ModelHub.HUGGING_FACE) {
                "连不上 huggingface.co。国内网络请改用「HF 镜像」，同一个链接就能用。"
            } else {
                "连不上${hub.label}，检查一下网络"
            }

        private fun listError(
            link: ModelLink,
            code: Int,
        ): String =
            when (code) {
                // Hugging Face answers 401 for repositories that do not exist, ModelScope 200 with an error code.
                HTTP_UNAUTHORIZED, HTTP_NOT_FOUND -> "在${link.hub.label}上找不到「${link.repo}」，检查链接或换一个源"

                else -> "读取文件列表失败（HTTP $code）"
            }

        private companion object {
            val HTTP_OK_RANGE = 200..299
            const val HTTP_UNAUTHORIZED = 401
            const val HTTP_NOT_FOUND = 404
        }
    }

/** [HubHttp] over [HttpURLConnection]; https to https redirects are followed by the platform. */
class UrlConnectionHubHttp : HubHttp {
    override fun text(url: String): Pair<Int, String> {
        val conn = open(url)
        return try {
            val code = conn.responseCode
            val body = stream(conn, code)?.use { read(it, MAX_LIST_BYTES) } ?: ByteArray(0)
            code to String(body, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    override fun prefix(
        url: String,
        bytes: Int,
    ): Pair<Int, ByteArray> {
        val conn = open(url).apply { setRequestProperty("Range", "bytes=0-${bytes - 1}") }
        return try {
            val code = conn.responseCode
            code to (stream(conn, code)?.use { read(it, bytes) } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "only https addresses are read" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    private fun stream(
        conn: HttpURLConnection,
        code: Int,
    ): InputStream? = if (code >= HTTP_ERROR) conn.errorStream else conn.inputStream

    private fun read(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0
        while (total < limit) {
            val n = input.read(buffer, 0, minOf(BUFFER, limit - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_LIST_BYTES = 4 * 1024 * 1024
        const val BUFFER = 16 * 1024
        const val CONNECT_MS = 10_000
        const val READ_MS = 20_000
        const val HTTP_ERROR = 400
        const val USER_AGENT = "PhoneMCP-ARC"
    }
}
