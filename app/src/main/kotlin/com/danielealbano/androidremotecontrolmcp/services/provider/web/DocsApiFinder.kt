package com.danielealbano.androidremotecontrolmcp.services.provider.web

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind

/** A base address found on a page, with the wire format it seems to speak. */
data class ApiCandidate(
    val baseUrl: String,
    val kind: ProviderKind,
)

/**
 * What a service's documentation page says about its API: the likeliest base addresses (best
 * first), the service's name, and the model its examples use.
 */
data class DocsFinding(
    val name: String,
    val candidates: List<ApiCandidate>,
    val exampleModel: String,
)

/**
 * Reads a documentation page with fixed rules, no language model. Base addresses are taken from
 * the links and code samples on the page, scored by how often they appear and whether a request
 * example (".../chat/completions") points at them. Only addresses owned by the page's own company
 * are kept ([ApiUrls.sameOwner]): pages quote other companies' APIs in examples all the time.
 */
object DocsApiFinder {
    private val URL = Regex("https?://[A-Za-z0-9.-]+(?::\\d+)?(?:/[A-Za-z0-9._~%/+-]*)?")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val MODEL = Regex("[\"']model[\"']\\s*:\\s*[\"']([A-Za-z0-9._:/-]{2,80})[\"']")

    /** " | ", " - ", "–", and a bare "-" between Chinese text ("…服务平台百炼(Model Studio)-阿里云帮助中心"). */
    private val TITLE_SEPARATORS = Regex("\\s[-–—·]\\s|\\s*[|–—]\\s*|(?<=[\\u4e00-\\u9fff)）])-(?=[\\u4e00-\\u9fff(（])")

    /** Words that describe the page, not the company: stripped from the title to get a name. */
    private val TITLE_NOISE =
        listOf(
            "开放文档",
            "开放平台",
            "文档中心",
            "文档",
            "帮助中心",
            "快速开始",
            "快速上手",
            "Documentation",
            "Docs",
            "Quickstart",
            "Guide",
            "API",
        )

    private const val ENDPOINT_WEIGHT = 5
    private const val MAX_NAME = 24
    private const val MAX_CANDIDATES = 3

    fun find(
        pageUrl: String,
        page: String,
    ): DocsFinding {
        val text = unescape(page)
        val pageHost = ApiUrls.hostOf(pageUrl).orEmpty()
        val scores = mutableMapOf<String, Int>()
        val routes = mutableMapOf<String, MutableSet<String>>()
        URL.findAll(text).forEach { match ->
            val url = match.value.trimEnd('.', ',')
            val base = ApiUrls.baseOf(url) ?: return@forEach
            val host = ApiUrls.hostOf(base) ?: return@forEach
            if (pageHost.isNotEmpty() && !ApiUrls.sameOwner(pageHost, host)) return@forEach
            val endpoint = ApiUrls.endpointOf(url)
            scores[base] = scores.getOrDefault(base, 0) + if (endpoint != null) ENDPOINT_WEIGHT else 1
            endpoint?.let { routes.getOrPut(base) { mutableSetOf() }.add(it) }
        }
        val candidates =
            scores.entries
                .sortedByDescending { it.value }
                .take(MAX_CANDIDATES)
                .map { (base, _) -> ApiCandidate(base, kindOf(base, routes[base].orEmpty())) }
        return DocsFinding(nameOf(text, pageHost), candidates, exampleModelOf(text))
    }

    /** Anthropic when the address says so or only "/messages" requests point at it. */
    private fun kindOf(
        base: String,
        routes: Set<String>,
    ): ProviderKind {
        val onlyMessages = routes.isNotEmpty() && routes.all { it == "/messages" }
        return if (base.endsWith("/anthropic") || onlyMessages) ProviderKind.ANTHROPIC else ProviderKind.OPENAI
    }

    /** The site's name from the page title ("Provider API | Command Code - Command Code Docs" → "Command Code"). */
    private fun nameOf(
        text: String,
        pageHost: String,
    ): String {
        val title =
            TITLE
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()
        val fromTitle =
            title
                .split(TITLE_SEPARATORS)
                .map { part -> TITLE_NOISE.fold(part) { acc, noise -> acc.replace(noise, "", ignoreCase = true) }.trim() }
                .lastOrNull { it.isNotBlank() }
        val fromHost = ApiUrls.siteOf(pageHost).substringBefore('.').replaceFirstChar { it.uppercase() }
        return (fromTitle ?: fromHost).take(MAX_NAME)
    }

    /** The model most examples on the page ask for, if any. */
    private fun exampleModelOf(text: String): String =
        MODEL
            .findAll(text)
            .map { it.groupValues[1] }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            .orEmpty()

    /** Pages embed code in JSON and HTML: "https:\/\/…", "&quot;model&quot;". */
    private fun unescape(page: String): String =
        page
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
}
