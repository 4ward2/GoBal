package com.danielealbano.androidremotecontrolmcp.services.memory

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Text pulled out of a document, and whether OCR had to be used to get it. */
data class Extracted(
    val text: String,
    val ocr: Boolean = false,
)

/**
 * Pure extractors for the formats that need no Android APIs (plain text, Markdown, HTML, DOCX),
 * so they are unit-tested on the JVM. PDF and image OCR live in [DocumentReader].
 */
object TextExtractors {
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val BUFFER = 16 * 1024
    private const val DOCX_DOCUMENT = "word/document.xml"
    private val RUN_TEXT = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
    private val DOCX_TOKENS =
        Regex("<w:t(?:\\s[^>]*)?>.*?</w:t>|</w:p>|<w:br[^>]*/>|<w:cr[^>]*/>|<w:tab[^>]*/>", RegexOption.DOT_MATCHES_ALL)
    private val ANY_TAG = Regex("<[^>]+>")
    private val HTML_BLOCK_END =
        Regex("</(p|div|li|h[1-6]|tr|br|section|article)>|<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val HTML_SKIP =
        Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BLANK_LINES = Regex("\\n{3,}")

    /** Extensions handled without OCR or PDF parsing. */
    val PLAIN_EXTENSIONS =
        setOf("txt", "md", "markdown", "csv", "tsv", "json", "log", "xml", "yaml", "yml", "ini", "srt", "vtt", "rtf")
    val HTML_EXTENSIONS = setOf("html", "htm")
    const val DOCX_EXTENSION = "docx"

    fun plainText(stream: InputStream): Extracted = Extracted(tidy(readAll(stream).decodeToString()))

    fun html(stream: InputStream): Extracted {
        val raw = readAll(stream).decodeToString()
        val text =
            raw
                .replace(HTML_SKIP, "")
                .replace(HTML_BLOCK_END, "\n")
                .replace(ANY_TAG, "")
                .let(::unescape)
        return Extracted(tidy(text))
    }

    /** DOCX is a zip; the body is `word/document.xml`, whose `<w:t>` runs carry the text. */
    fun docx(stream: InputStream): Extracted {
        val xml = zipEntry(stream, DOCX_DOCUMENT) ?: throw IllegalArgumentException("不是有效的 .docx 文件")
        return Extracted(tidy(docxText(xml)))
    }

    /** Walks the XML once so run text, tabs and paragraph breaks keep their order. */
    private fun docxText(xml: String): String {
        val out = StringBuilder()
        DOCX_TOKENS.findAll(xml).forEach { match ->
            val token = match.value
            when {
                token.startsWith("<w:tab") -> {
                    out.append('\t')
                }

                token.startsWith("<w:t") -> {
                    RUN_TEXT
                        .find(token)
                        ?.groupValues
                        ?.get(1)
                        ?.let { out.append(unescape(it)) }
                }

                else -> {
                    out.append('\n')
                }
            }
        }
        return out.toString()
    }

    fun unescape(text: String): String =
        text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

    /** Collapses runs of blank lines and trims, keeping paragraphs readable for the model. */
    fun tidy(text: String): String =
        text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(BLANK_LINES, "\n\n")
            .trim()

    private fun zipEntry(
        stream: InputStream,
        name: String,
    ): String? {
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) return readAll(zip).decodeToString()
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun readAll(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        var n = stream.read(buffer)
        while (n >= 0) {
            total += n
            require(total <= MAX_BYTES) { "文件太大（超过 20MB）" }
            out.write(buffer, 0, n)
            n = stream.read(buffer)
        }
        return out.toByteArray()
    }
}
