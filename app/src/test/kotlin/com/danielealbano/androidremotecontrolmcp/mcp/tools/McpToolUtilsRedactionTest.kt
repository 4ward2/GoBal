package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.vault.ContentRedactor
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultRedactor
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("McpToolUtils vault redaction")
class McpToolUtilsRedactionTest {
    @BeforeEach
    fun setUp() {
        McpToolUtils.contentRedactor = VaultRedactor(mapOf("phone" to "13800138000"))
    }

    @AfterEach
    fun tearDown() {
        McpToolUtils.contentRedactor = ContentRedactor.NONE
    }

    @Test
    fun `untrusted text is redacted and keeps the warning first`() {
        val t = (McpToolUtils.untrustedTextResult("号码 13800138000").content.single() as TextContent).text
        assertTrue(t.startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
        assertTrue(t.endsWith("号码 ‹vault:phone›"))
    }

    @Test
    fun `untrusted text plus image is redacted`() {
        val r = McpToolUtils.untrustedTextAndImageResult("13800138000", "AAAA", "image/png")
        assertFalse((r.content[0] as TextContent).text.contains("13800138000"))
        assertTrue(r.content[1] is ImageContent)
    }

    @Test
    fun `mixed untrusted content redacts text blocks only`() {
        val r =
            McpToolUtils.untrustedResult(
                listOf(TextContent(text = "call 138 0013 8000"), ImageContent(data = "AAAA", mimeType = "image/png")),
            )
        assertEquals("call ‹vault:phone›", (r.content[1] as TextContent).text)
        assertTrue(r.content[2] is ImageContent)
    }

    @Test
    fun `server generated text is not touched`() {
        val t = (McpToolUtils.textResult("13800138000").content.single() as TextContent).text
        assertEquals("13800138000", t)
    }
}
