package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.SurroundingText
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Password fields are never echoed by the type tools")
class PasswordFieldEchoTest {
    private fun setServiceInstance(service: McpAccessibilityService?) {
        val field = McpAccessibilityService::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, service)
    }

    @AfterEach
    fun tearDown() {
        setServiceInstance(null)
    }

    private fun focusedField(password: Boolean) {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isPassword } returns password
        val service = mockk<McpAccessibilityService>(relaxed = true)
        every { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns node
        setServiceInstance(service)
    }

    @Test
    fun `password field content is hidden and never read`() {
        focusedField(password = true)
        val controller = mockk<TypeInputController>(relaxed = true)
        assertEquals(PASSWORD_HIDDEN, readFieldContent(controller))
        verify(exactly = 0) { controller.getSurroundingText(any(), any(), any()) }
    }

    @Test
    fun `ordinary field content is returned`() {
        focusedField(password = false)
        val text = mockk<SurroundingText>()
        every { text.text } returns "hello"
        val controller = mockk<TypeInputController>()
        every { controller.getSurroundingText(any(), any(), any()) } returns text
        assertEquals("hello", readFieldContent(controller))
    }
}
