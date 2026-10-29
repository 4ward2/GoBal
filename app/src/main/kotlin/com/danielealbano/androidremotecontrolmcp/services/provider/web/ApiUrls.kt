package com.danielealbano.androidremotecontrolmcp.services.provider.web

import java.net.URI

/**
 * Rules for telling a model API's base address from any other link, used on pasted text and on
 * documentation pages alike. No network: shape only.
 */
object ApiUrls {
    /** Endpoints that sit under a base address; stripped to get back to the base. */
    private val ENDPOINTS =
        listOf(
            "/chat/completions",
            "/completions",
            "/responses",
            "/messages",
            "/models",
            "/embeddings",
            "/images/generations",
            "/audio/speech",
            "/audio/transcriptions",
            "/systemone",
        )

    /** Paths that end the way a base address does: "/v1", "/api/v3", "/compatible-mode/v1", "/openai". */
    private val BASE_PATH = Regex("(?:^|/)(?:v\\d+(?:beta|alpha)?|openai|anthropic)$")

    private val ASSET_EXTENSIONS =
        listOf(".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".css", ".js", ".mjs", ".woff", ".woff2", ".ttf", ".pdf", ".mp4")

    /** Hosts that are never a model API even when their paths look like one. */
    private val NOT_API_HOSTS =
        listOf("cdn", "cloudfront", "fonts.", "googletagmanager", "analytics", "github.com", "gitee.com", "npmjs", "pypi.org", "youtube")

    /** Path words of documentation and web pages, not APIs. */
    private val PAGE_WORDS = listOf("/docs", "/doc/", "/guide", "/blog", "/help", "/console", "/pricing", "/og")

    /** Second-level labels that belong to the country code after them ("com.cn", "co.uk"). */
    private val SHARED_SECOND_LEVEL = setOf("com", "net", "org", "gov", "edu", "co", "ac")

    private const val MIN_SHARED_PREFIX = 4

    /**
     * The base address [url] points into, e.g. ".../v1/chat/completions" → ".../v1", or null when it
     * does not look like a model API at all.
     */
    fun baseOf(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val scheme = uri.scheme?.lowercase()
        val path = stripEndpoint(uri.path.orEmpty().trimEnd('/'))
        val lowerPath = path.lowercase()
        val apiHost = host.startsWith("api.") || host.startsWith("api-") || host.contains(".api.")
        val shaped = BASE_PATH.containsMatchIn(lowerPath) || (apiHost && lowerPath.isEmpty())
        val rejected =
            scheme !in setOf("http", "https") ||
                host.isEmpty() ||
                host.contains("docs") ||
                NOT_API_HOSTS.any { host.contains(it) } ||
                ASSET_EXTENSIONS.any { lowerPath.endsWith(it) } ||
                PAGE_WORDS.any { lowerPath.contains(it) }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return if (!rejected && shaped) "$scheme://$host$port$path" else null
    }

    /** The endpoint [url] ends with ("/messages"), if any. */
    fun endpointOf(url: String): String? {
        val path = runCatching { URI(url.trim()).path }.getOrNull().orEmpty().trimEnd('/')
        return ENDPOINTS.firstOrNull { path.endsWith(it) }
    }

    private fun stripEndpoint(path: String): String {
        val endpoint = ENDPOINTS.firstOrNull { path.endsWith(it) } ?: return path
        return path.removeSuffix(endpoint).trimEnd('/')
    }

    /** "api.commandcode.ai" → "commandcode.ai"; "open.bigmodel.cn" → "bigmodel.cn"; "x.com.cn" → "x.com.cn". */
    fun siteOf(host: String): String {
        val labels =
            host
                .lowercase()
                .trim('.')
                .split('.')
                .filter { it.isNotEmpty() }
        val keep = if (labels.size >= 3 && labels[labels.size - 2] in SHARED_SECOND_LEVEL) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    /**
     * Whether an API on [apiHost] belongs to the same company as a page on [pageHost]: the same site,
     * or sites whose names start alike ("aliyun.com" docs for "aliyuncs.com" APIs). A key is only
     * ever tried against addresses that pass this, never against another company named in examples.
     */
    fun sameOwner(
        pageHost: String,
        apiHost: String,
    ): Boolean {
        val page = siteOf(pageHost)
        val api = siteOf(apiHost)
        val pageName = page.substringBefore('.')
        val apiName = api.substringBefore('.')
        val alike =
            minOf(pageName.length, apiName.length) >= MIN_SHARED_PREFIX &&
                (pageName.startsWith(apiName) || apiName.startsWith(pageName))
        return page == api || alike
    }

    fun hostOf(url: String): String? = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }
}
