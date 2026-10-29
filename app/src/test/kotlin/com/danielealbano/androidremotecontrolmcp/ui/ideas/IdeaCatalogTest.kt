package com.danielealbano.androidremotecontrolmcp.ui.ideas

import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("IdeaCatalog")
class IdeaCatalogTest {
    @Test
    fun `every idea is routed to the phone agent rather than to plain chat`() {
        val notRouted =
            IdeaCatalog.categories
                .flatMap { it.ideas }
                .map { it.text }
                .filterNot(PhoneMcpChatLogic::isPhoneControlIntent)
        assertTrue(notRouted.isEmpty(), "not phone-control intents: $notRouted")
    }

    @Test
    fun `ideas needing a missing app are hidden and empty categories dropped`() {
        val onlyMeituan = IdeaCatalog.available { it == "com.sankuai.meituan" }
        assertEquals(listOf("外卖美食", "手机操作"), onlyMeituan.map { it.title })
    }

    @Test
    fun `everything is offered when every app is installed`() {
        assertEquals(IdeaCatalog.categories, IdeaCatalog.available { true })
    }

    @Test
    fun `quick starts take the first idea of each available category up to the limit`() {
        val quick = IdeaCatalog.quickStarts(isInstalled = { true }, limit = 2)
        assertEquals(IdeaCatalog.categories.take(2).map { it.ideas.first() }, quick)
    }
}
