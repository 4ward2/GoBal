package com.danielealbano.androidremotecontrolmcp.services.channel

import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelEvent
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

@DisplayName("EventDispatcherImpl")
class EventDispatcherImplTest {
    private val testEvent =
        ChannelEvent(
            type = "notification",
            timestamp = "2026-04-08T12:00:00Z",
            data =
                buildJsonObject {
                    put("test", "data")
                },
        )

    @Nested
    @DisplayName("initial state")
    inner class InitialState {
        @Test
        fun `status starts as Idle`() {
            val dispatcher = EventDispatcherImpl()
            assertEquals(ChannelConnectionStatus.Idle, dispatcher.connectionStatus.value)
        }
    }

    @Nested
    @DisplayName("dispatch lifecycle")
    inner class DispatchLifecycle {
        @Test
        fun `dispatch before start returns failure`() =
            runTest {
                val dispatcher = EventDispatcherImpl()
                val result = dispatcher.dispatch(testEvent)
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
            }

        @Test
        fun `dispatch after stop returns failure`() =
            runTest {
                val dispatcher = EventDispatcherImpl()
                dispatcher.start("http://localhost:9090", "test-token")
                dispatcher.stop()
                val result = dispatcher.dispatch(testEvent)
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
            }

        @Test
        fun `stop resets status to Idle`() {
            val dispatcher = EventDispatcherImpl()
            dispatcher.start("http://localhost:9090", "test-token")
            dispatcher.stop()
            assertEquals(ChannelConnectionStatus.Idle, dispatcher.connectionStatus.value)
        }
    }

    @Nested
    @DisplayName("dispatch with server")
    inner class DispatchWithServer {
        @Test
        fun `dispatch sends POST with correct headers and body`() =
            runTest {
                val receivedAuth = AtomicReference<String?>(null)
                val receivedContentType = AtomicReference<String?>(null)
                val receivedBody = AtomicReference<String?>(null)

                val server =
                    embeddedServer(Netty, port = 0) {
                        routing {
                            post("/event") {
                                receivedAuth.set(call.request.header("Authorization"))
                                receivedContentType.set(call.request.header("Content-Type"))
                                receivedBody.set(call.receiveText())
                                call.respond(HttpStatusCode.OK, """{"status":"ok"}""")
                            }
                        }
                    }
                server.start(wait = false)
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port

                try {
                    val dispatcher = EventDispatcherImpl()
                    dispatcher.start("http://localhost:$port", "my-secret-token")
                    val result = dispatcher.dispatch(testEvent)

                    assertTrue(result.isSuccess)
                    assertEquals("Bearer my-secret-token", receivedAuth.get())
                    assertNotNull(receivedContentType.get())
                    assertTrue(receivedContentType.get()!!.contains("application/json"))

                    val body = receivedBody.get()
                    assertNotNull(body)
                    val parsed = Json.decodeFromString<ChannelEvent>(body!!)
                    assertEquals("notification", parsed.type)
                    assertEquals("2026-04-08T12:00:00Z", parsed.timestamp)

                    dispatcher.stop()
                } finally {
                    server.stop(0, 0)
                }
            }

        @Test
        fun `dispatch with empty auth token sends no Authorization header`() =
            runTest {
                val receivedAuth = AtomicReference<String?>(null)

                val server =
                    embeddedServer(Netty, port = 0) {
                        routing {
                            post("/event") {
                                receivedAuth.set(call.request.header("Authorization"))
                                call.respond(HttpStatusCode.OK, """{"status":"ok"}""")
                            }
                        }
                    }
                server.start(wait = false)
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port

                try {
                    val dispatcher = EventDispatcherImpl()
                    dispatcher.start("http://localhost:$port", "")
                    val result = dispatcher.dispatch(testEvent)

                    assertTrue(result.isSuccess)
                    assertEquals(null, receivedAuth.get())

                    dispatcher.stop()
                } finally {
                    server.stop(0, 0)
                }
            }

        @Test
        fun `dispatch updates status to Active on success`() =
            runTest {
                val server =
                    embeddedServer(Netty, port = 0) {
                        routing {
                            post("/event") {
                                call.respond(HttpStatusCode.OK, """{"status":"ok"}""")
                            }
                        }
                    }
                server.start(wait = false)
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port

                try {
                    val dispatcher = EventDispatcherImpl()
                    dispatcher.start("http://localhost:$port", "token")
                    dispatcher.dispatch(testEvent)

                    assertEquals(ChannelConnectionStatus.Active, dispatcher.connectionStatus.value)
                    dispatcher.stop()
                } finally {
                    server.stop(0, 0)
                }
            }

        @Test
        fun `dispatch updates status to Error on HTTP error`() =
            runTest {
                val server =
                    embeddedServer(Netty, port = 0) {
                        routing {
                            post("/event") {
                                call.respond(HttpStatusCode.InternalServerError, "error")
                            }
                        }
                    }
                server.start(wait = false)
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port

                try {
                    val dispatcher = EventDispatcherImpl()
                    dispatcher.start("http://localhost:$port", "token")
                    val result = dispatcher.dispatch(testEvent)

                    assertTrue(result.isFailure)
                    assertTrue(dispatcher.connectionStatus.value is ChannelConnectionStatus.Error)
                    dispatcher.stop()
                } finally {
                    server.stop(0, 0)
                }
            }

        @Test
        fun `dispatch updates status to Error on network failure`() =
            runTest {
                val dispatcher = EventDispatcherImpl()
                dispatcher.start("http://localhost:1", "test-token")
                val result = dispatcher.dispatch(testEvent)
                assertTrue(result.isFailure)
                assertTrue(dispatcher.connectionStatus.value is ChannelConnectionStatus.Error)
            }
    }

    @Nested
    @DisplayName("health check")
    inner class HealthCheck {
        @Test
        fun `healthCheck before start returns failure`() =
            runTest {
                val dispatcher = EventDispatcherImpl()
                val result = dispatcher.healthCheck()
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
            }

        @Test
        fun `healthCheck updates status to Active on success`() =
            runTest {
                val server =
                    embeddedServer(Netty, port = 0) {
                        routing {
                            get("/health") {
                                call.respond(HttpStatusCode.OK, """{"status":"ok"}""")
                            }
                        }
                    }
                server.start(wait = false)
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port

                try {
                    val dispatcher = EventDispatcherImpl()
                    dispatcher.start("http://localhost:$port", "token")
                    val result = dispatcher.healthCheck()

                    assertTrue(result.isSuccess)
                    assertEquals(ChannelConnectionStatus.Active, dispatcher.connectionStatus.value)
                    dispatcher.stop()
                } finally {
                    server.stop(0, 0)
                }
            }

        @Test
        fun `healthCheck updates status to Error on failure`() =
            runTest {
                val dispatcher = EventDispatcherImpl()
                dispatcher.start("http://localhost:1", "token")
                val result = dispatcher.healthCheck()

                assertTrue(result.isFailure)
                assertTrue(dispatcher.connectionStatus.value is ChannelConnectionStatus.Error)
            }
    }
}
