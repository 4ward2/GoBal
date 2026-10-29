package com.danielealbano.androidremotecontrolmcp.services.provider.web

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import javax.inject.Inject

/** A fetched web page: where it ended up after redirects, its status, type and text. */
data class WebPage(
    val url: String,
    val code: Int,
    val contentType: String,
    val text: String,
)

/** Reads a public web page. Never sends credentials: pages are fetched exactly as a browser would. */
interface WebPages {
    fun get(url: String): WebPage
}

/**
 * [WebPages] over [HttpURLConnection]. Redirects are followed by hand (the platform will not go
 * from http to https on its own), only to http/https, at most [MAX_REDIRECTS] times; the body is
 * cut at [MAX_BYTES] since documentation pages can be large and only their text matters.
 */
class UrlConnectionWebPages
    @Inject
    constructor() : WebPages {
        override fun get(url: String): WebPage {
            var current = url
            repeat(MAX_REDIRECTS) {
                val conn = open(current)
                try {
                    val code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    if (code in REDIRECTS && location != null) {
                        current = URI(current).resolve(location).toString()
                        require(current.startsWith("http://") || current.startsWith("https://")) { "unsupported redirect" }
                        return@repeat
                    }
                    val type = conn.contentType.orEmpty()
                    val body = (if (code >= HTTP_ERROR) conn.errorStream else conn.inputStream)?.use { read(it) } ?: ByteArray(0)
                    return WebPage(current, code, type, String(body, charsetOf(type)))
                } finally {
                    conn.disconnect()
                }
            }
            error("too many redirects")
        }

        private fun open(url: String): HttpURLConnection {
            require(url.startsWith("http://") || url.startsWith("https://")) { "only web addresses can be read" }
            return (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            }
        }

        private fun read(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            var total = 0
            while (total < MAX_BYTES) {
                val n = input.read(buffer, 0, minOf(BUFFER, MAX_BYTES - total))
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
            return out.toByteArray()
        }

        private fun charsetOf(contentType: String): Charset {
            val name =
                contentType
                    .substringAfter("charset=", "")
                    .substringBefore(';')
                    .trim()
                    .trim('"')
            return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }

        private companion object {
            const val MAX_REDIRECTS = 5
            const val MAX_BYTES = 3 * 1024 * 1024
            const val BUFFER = 16 * 1024
            const val CONNECT_MS = 10_000
            const val READ_MS = 20_000
            const val HTTP_ERROR = 400
            val REDIRECTS = 300..308
            const val USER_AGENT = "Mozilla/5.0 (Linux; Android) PhoneMCP-ARC"
        }
    }
