package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatHttpServerTest {
    @Test
    fun shellSingleQuote_escapesEmbeddedQuotes() {
        assertEquals("'hello'", ChatHttpServer.shellSingleQuote("hello"))
        assertEquals("'it'\\''s'", ChatHttpServer.shellSingleQuote("it's"))
    }

    @Test
    fun errorJson_includesMessage() {
        val json = ChatHttpServer.errorJson("boom")
        assertEquals(true, json.contains("\"ok\":false"))
        assertEquals(true, json.contains("boom"))
    }

    @Test
    fun isLocalPortListening_detectsAnOpenLoopbackPort() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            assertEquals(true, ChatHttpServer.isLocalPortListening(server.localPort))
        }
    }

    @Test
    fun isLocalPortListening_isFalseOnceThePortCloses() {
        val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertEquals(false, ChatHttpServer.isLocalPortListening(port))
    }
}
