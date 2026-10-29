package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PhoneHandoff")
class PhoneHandoffTest {
    @Test
    fun `a reply that hands a phone task over is told from an answer`() {
        assertEquals("在美团拼好饭里找最便宜的奶茶", PhoneHandoff.taskIn("<phone_task>在美团拼好饭里找最便宜的奶茶</phone_task>"))
        assertEquals("打开设置", PhoneHandoff.taskIn("好的。\n<PHONE_TASK> 打开设置 </PHONE_TASK>"))
        assertEquals("打开设", PhoneHandoff.taskIn("<phone_task>打开设"))
        assertNull(PhoneHandoff.taskIn("拼好饭在美团首页的入口里，点进去就能看到。"))
    }

    @Test
    fun `while it streams in, nothing from the tag on is shown`() {
        assertEquals("", StreamingText.visible("<phone_task>在美团里"))
        assertEquals("", StreamingText.visible("<phone_t"))
        assertEquals("好的", StreamingText.visible("好的\n<phone_task>打开设置</phone_task>"))
    }
}
