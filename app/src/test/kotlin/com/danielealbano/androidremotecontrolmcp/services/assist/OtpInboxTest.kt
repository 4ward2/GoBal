package com.danielealbano.androidremotecontrolmcp.services.assist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OtpInbox")
class OtpInboxTest {
    private val inbox = OtpInbox(ttlMs = 1_000L)

    @Test
    fun `offered code is pending until it expires`() {
        assertTrue(inbox.offer("482913", "sms", now = 0L))
        assertEquals("482913", inbox.peek(now = 1_000L)?.code)
        assertNull(inbox.peek(now = 1_001L))
    }

    @Test
    fun `same code from a second source is not re-offered`() {
        assertTrue(inbox.offer("482913", "sms", now = 0L))
        assertFalse(inbox.offer("482913", "com.android.messaging", now = 10L))
        assertEquals("sms", inbox.peek(now = 10L)?.source)
    }

    @Test
    fun `newer code replaces the pending one`() {
        inbox.offer("111111", "sms", now = 0L)
        assertTrue(inbox.offer("222222", "sms", now = 10L))
        assertEquals("222222", inbox.peek(now = 10L)?.code)
    }

    @Test
    fun `consumed code is cleared and never re-armed`() {
        inbox.offer("482913", "sms", now = 0L)
        inbox.consume("482913")
        assertNull(inbox.peek(now = 1L))
        assertFalse(inbox.offer("482913", "notification", now = 2L))
    }
}
