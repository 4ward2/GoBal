package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.utils.ApiLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NodeTextEditTest {
    @AfterEach
    fun newestAgain() {
        ApiLevel.pretend(null)
    }

    @Test
    fun `appending adds to whatever the field holds`() {
        assertEquals("hello world", NodeTextEdit.Append(" world").applyTo("hello"))
        assertEquals("x", NodeTextEdit.Append("x").applyTo(""))
    }

    @Test
    fun `inserting goes where asked, and refuses a place past the end`() {
        assertEquals("heXllo", NodeTextEdit.Insert("X", 2).applyTo("hello"))
        assertEquals("helloX", NodeTextEdit.Insert("X", 5).applyTo("hello"))
        assertThrows<McpToolException.InvalidParams> { NodeTextEdit.Insert("X", 6).applyTo("hello") }
    }

    @Test
    fun `replacing changes the first match only, and says when there is none`() {
        assertEquals("a-b b", NodeTextEdit.Replace(" ", "-").applyTo("a b b"))
        assertEquals("ab", NodeTextEdit.Replace("x", "").applyTo("axb"))
        assertThrows<McpToolException.ActionFailed> { NodeTextEdit.Replace("z", "y").applyTo("abc") }
    }

    @Test
    fun `clearing leaves nothing`() {
        assertEquals("", NodeTextEdit.Clear.applyTo("anything"))
    }

    @Test
    fun `only phones before Android 13 type this way`() {
        assertFalse(NodeTextInput.needed())
        ApiLevel.pretend(29)
        assertTrue(NodeTextInput.needed())
        ApiLevel.pretend(33)
        assertFalse(NodeTextInput.needed())
    }
}
