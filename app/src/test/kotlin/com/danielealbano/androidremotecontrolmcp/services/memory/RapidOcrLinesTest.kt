package com.danielealbano.androidremotecontrolmcp.services.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RapidOcrHttp.linesOf")
class RapidOcrLinesTest {
    private val ocr = RapidOcrHttp("http://127.0.0.1:1")

    @Test
    fun `boxes on the same row join with a space, rows become lines`() {
        val body =
            """{"ok":true,"items":[
              {"text":"右边","x":300,"y":102,"w":50,"h":30},
              {"text":"左边","x":10,"y":100,"w":50,"h":30},
              {"text":"第二行","x":12,"y":180,"w":80,"h":30},
              {"text":"  ","x":0,"y":0,"w":1,"h":1}
            ]}"""
        assertEquals("左边 右边\n第二行", ocr.linesOf(body))
    }

    @Test
    fun `no items means empty text and garbage means null`() {
        assertEquals("", ocr.linesOf("""{"ok":true,"items":[]}"""))
        assertNull(ocr.linesOf("<html>"))
    }
}
