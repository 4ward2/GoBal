package com.danielealbano.androidremotecontrolmcp.services.provider.web

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ApiUrls and DocsApiFinder")
class DocsApiFinderTest {
    @Test
    fun `base addresses are recognised and endpoints stripped`() {
        assertEquals("https://api.commandcode.ai/provider/v1", ApiUrls.baseOf("https://api.commandcode.ai/provider/v1/chat/completions"))
        assertEquals("https://api.deepseek.com", ApiUrls.baseOf("https://api.deepseek.com/chat/completions"))
        assertEquals("https://open.bigmodel.cn/api/paas/v4", ApiUrls.baseOf("https://open.bigmodel.cn/api/paas/v4/"))
        assertEquals("https://openrouter.ai/api/v1", ApiUrls.baseOf("https://openrouter.ai/api/v1"))
        assertEquals("http://192.168.1.5:8000/v1", ApiUrls.baseOf("http://192.168.1.5:8000/v1/models"))
        assertEquals("/messages", ApiUrls.endpointOf("https://api.x.com/v1/messages"))
    }

    @Test
    fun `pages, assets and docs hosts are not APIs`() {
        assertNull(ApiUrls.baseOf("https://commandcode.ai/docs/provider"))
        assertNull(ApiUrls.baseOf("https://api-docs.deepseek.com/"))
        assertNull(ApiUrls.baseOf("https://cdn.example.com/fontawesome/v7"))
        assertNull(ApiUrls.baseOf("https://example.com/icons/v2/logo.svg"))
        assertNull(ApiUrls.baseOf("https://example.com/about"))
        assertNull(ApiUrls.baseOf("ftp://api.example.com/v1"))
    }

    @Test
    fun `the same company may use related site names, others never count`() {
        assertEquals("bigmodel.cn", ApiUrls.siteOf("open.bigmodel.cn"))
        assertEquals("example.com.cn", ApiUrls.siteOf("api.example.com.cn"))
        assertTrue(ApiUrls.sameOwner("docs.siliconflow.cn", "api.siliconflow.cn"))
        assertTrue(ApiUrls.sameOwner("help.aliyun.com", "dashscope.aliyuncs.com"))
        assertFalse(ApiUrls.sameOwner("commandcode.ai", "api.openai.com"))
        assertFalse(ApiUrls.sameOwner("x.ai", "api.xiaomimimo.com"))
    }

    @Test
    fun `a docs page gives its own API address, name and example model, ignoring other companies`() {
        val page =
            """
            <html><head><title>Provider API | Command Code - Command Code Docs</title></head><body>
            <p>Base URL: https://api.commandcode.ai/provider/v1</p>
            <pre>curl https://api.commandcode.ai/provider/v1/chat/completions -d '{"model": "deepseek/deepseek-v4-flash"}'</pre>
            <pre>client = OpenAI(base_url="https://api.openai.com/v1")</pre>
            <a href="https://platform.claude.com/docs/en/api/messages/create">Anthropic</a>
            <script>{"href":"https:\/\/api.commandcode.ai\/provider\/v1\/models"}</script>
            <img src="https://d3gk.cloudfront.net/fontawesome/v7.2.0/solid/x.svg">
            </body></html>
            """.trimIndent()
        val finding = DocsApiFinder.find("https://commandcode.ai/docs/provider", page)
        assertEquals("Command Code", finding.name)
        assertEquals("deepseek/deepseek-v4-flash", finding.exampleModel)
        assertEquals(listOf(ApiCandidate("https://api.commandcode.ai/provider/v1", ProviderKind.OPENAI)), finding.candidates)
    }

    @Test
    fun `an address only ever asked for messages is Anthropic, and the strongest comes first`() {
        val page =
            """
            <title>快速开始 - 某某 API 开放平台</title>
            https://api.example.com/v1/chat/completions https://api.example.com/v1/chat/completions
            https://api.example.com/anthropic/v1/messages
            https://api.example.com/v2
            """.trimIndent()
        val finding = DocsApiFinder.find("https://docs.example.com/start", page)
        assertEquals("某某", finding.name)
        assertEquals("https://api.example.com/v1", finding.candidates.first().baseUrl)
        assertEquals(
            ProviderKind.ANTHROPIC,
            finding.candidates.first { it.baseUrl == "https://api.example.com/anthropic/v1" }.kind,
        )
    }

    @Test
    fun `a Chinese title without spaced separators still yields the site name`() {
        val page = "<title>如何通过OpenAI接口调用千问模型-大模型服务平台百炼(Model Studio)-阿里云帮助中心</title>"
        assertEquals("阿里云", DocsApiFinder.find("https://help.aliyun.com/x", page).name)
    }

    @Test
    fun `a page with no API address finds nothing`() {
        val finding = DocsApiFinder.find("https://example.com", "<title>Example</title><a href=\"https://example.com/about\">")
        assertTrue(finding.candidates.isEmpty())
        assertEquals("Example", finding.name)
    }
}
