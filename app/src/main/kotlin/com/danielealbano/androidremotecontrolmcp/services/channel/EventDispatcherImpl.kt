package com.danielealbano.androidremotecontrolmcp.services.channel

import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelEvent
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventDispatcherImpl
    @Inject
    constructor() : EventDispatcher {
        private val _connectionStatus =
            MutableStateFlow<ChannelConnectionStatus>(ChannelConnectionStatus.Idle)
        override val connectionStatus: StateFlow<ChannelConnectionStatus> =
            _connectionStatus.asStateFlow()

        @Volatile
        private var client: HttpClient? = null

        @Volatile
        private var endpointUrl: String = ""

        @Volatile
        private var authToken: String = ""

        override fun start(
            endpointUrl: String,
            authToken: String,
        ) {
            this.endpointUrl = endpointUrl
            this.authToken = authToken
            // No Logging plugin installed — it would expose the auth token in logs
            client =
                HttpClient(OkHttp) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                    install(io.ktor.client.plugins.HttpTimeout) {
                        requestTimeoutMillis = REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    }
                }
            _connectionStatus.value = ChannelConnectionStatus.Idle
            Logger.i(TAG, "Event dispatcher started, endpoint=$endpointUrl")
        }

        override fun stop() {
            client?.close()
            client = null
            _connectionStatus.value = ChannelConnectionStatus.Idle
            Logger.i(TAG, "Event dispatcher stopped")
        }

        override suspend fun dispatch(event: ChannelEvent): Result<Unit> =
            withContext(Dispatchers.IO) {
                val httpClient =
                    client
                        ?: return@withContext Result.failure(
                            IllegalStateException("Dispatcher not started"),
                        )
                // Wi-Fi disconnect events fire while the LAN is already down; retry so they
                // (and connect races) still reach the PC once routing recovers.
                var lastFailure: Exception? = null
                repeat(MAX_ATTEMPTS) { attempt ->
                    try {
                        val response: HttpResponse =
                            httpClient.post("$endpointUrl/event") {
                                contentType(ContentType.Application.Json)
                                if (authToken.isNotEmpty()) {
                                    header("Authorization", "Bearer $authToken")
                                }
                                setBody(event)
                            }
                        if (response.status.isSuccess()) {
                            _connectionStatus.value = ChannelConnectionStatus.Active
                            if (attempt > 0) {
                                Logger.i(TAG, "Dispatch succeeded on attempt ${attempt + 1}")
                            }
                            return@withContext Result.success(Unit)
                        }
                        val msg = "HTTP ${response.status.value}"
                        lastFailure = IOException(msg)
                        Logger.w(TAG, "Dispatch failed (attempt ${attempt + 1}): $msg")
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        lastFailure = e
                        val msg = e.message ?: "Unknown error"
                        Logger.w(TAG, "Dispatch error (attempt ${attempt + 1}): $msg")
                    }
                    if (attempt < MAX_ATTEMPTS - 1) {
                        delay(retryDelayMs(attempt))
                    }
                }
                val failure = lastFailure ?: IOException("Dispatch failed")
                _connectionStatus.value =
                    ChannelConnectionStatus.Error(failure.message ?: "Unknown error")
                Result.failure(failure)
            }

        override suspend fun healthCheck(): Result<Unit> =
            withContext(Dispatchers.IO) {
                val httpClient =
                    client
                        ?: return@withContext Result.failure(
                            IllegalStateException("Dispatcher not started"),
                        )
                try {
                    val response: HttpResponse = httpClient.get("$endpointUrl/health")
                    if (response.status.isSuccess()) {
                        _connectionStatus.value = ChannelConnectionStatus.Active
                        Result.success(Unit)
                    } else {
                        val msg = "Health check failed: HTTP ${response.status.value}"
                        _connectionStatus.value = ChannelConnectionStatus.Error(msg)
                        Result.failure(IOException(msg))
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    val msg = e.message ?: "Unreachable"
                    _connectionStatus.value = ChannelConnectionStatus.Error(msg)
                    Result.failure(e)
                }
            }

        companion object {
            private const val TAG = "MCP:EventDispatcher"
            private const val REQUEST_TIMEOUT_MS = 5_000L
            private const val CONNECT_TIMEOUT_MS = 3_000L
            private const val MAX_ATTEMPTS = 6

            private fun retryDelayMs(attempt: Int): Long =
                // 1s, 2s, 4s, 6s, 8s ≈ 21s total wait (covers typical Wi-Fi reconnect)
                minOf(1_000L * (1 shl attempt.coerceAtMost(3)), 8_000L)
        }
    }
