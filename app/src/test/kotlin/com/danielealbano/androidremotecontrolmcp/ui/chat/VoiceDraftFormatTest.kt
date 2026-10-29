package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.services.vault.VaultCategory
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceDraft
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceLanguage
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceValueKind
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TabRequest
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VoiceDraftFormat")
class VoiceDraftFormatTest {
    private fun draft(
        kind: VoiceValueKind,
        value: String,
    ) = VoiceDraft("x", "x", VaultCategory.OTHER, false, kind, value, null, null)

    @Test
    fun `mobile numbers are grouped 3-4-4`() {
        assertEquals("138 1234 5678", VoiceDraftFormat.forReading(draft(VoiceValueKind.PHONE, "13812345678")))
    }

    @Test
    fun `other numbers are grouped by four and text is untouched`() {
        assertEquals("2341 2341 2346", VoiceDraftFormat.forReading(draft(VoiceValueKind.AADHAAR, "234123412346")))
        assertEquals("北京市 海淀区", VoiceDraftFormat.forReading(draft(VoiceValueKind.TEXT, "北京市 海淀区")))
        assertEquals("a@b.com", VoiceDraftFormat.forReading(draft(VoiceValueKind.EMAIL, "a@b.com")))
    }

    @Test
    fun `screen readers get one digit at a time`() {
        assertEquals("1 3 8", VoiceDraftFormat.forScreenReader(draft(VoiceValueKind.PHONE, "138")))
        assertEquals("张三", VoiceDraftFormat.forScreenReader(draft(VoiceValueKind.TEXT, "张三")))
    }

    @Test
    fun `every language has examples`() {
        VoiceLanguage.entries.forEach { assertTrue(VoiceDraftFormat.examples(it).isNotEmpty(), "$it") }
    }

    @Test
    fun `the removed ideas tab route falls back to the chat`() {
        assertEquals(TopLevelRoute.Chat.route, TabRequest.resolve("ideas").tabRoute)
    }
}
