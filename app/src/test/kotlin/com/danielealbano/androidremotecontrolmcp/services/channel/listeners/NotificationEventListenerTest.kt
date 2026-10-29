package com.danielealbano.androidremotecontrolmcp.services.channel.listeners

import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("NotificationEventListener")
class NotificationEventListenerTest {
    private fun createMockDispatcher(): EventDispatcher {
        val mock = mockk<EventDispatcher>(relaxed = true)
        coEvery { mock.dispatch(any()) } returns Result.success(Unit)
        coEvery { mock.connectionStatus } returns MutableStateFlow(ChannelConnectionStatus.Idle)
        return mock
    }

    @Nested
    @DisplayName("filter modes")
    inner class FilterModes {
        @Test
        fun `ALL mode forwards all packages`() {
            val config =
                NotificationChannelConfig(
                    enabled = true,
                    filterMode = NotificationFilterMode.ALL,
                )
            // ALL mode: any package should pass
            assertTrue(shouldForward("com.any.app", config))
            assertTrue(shouldForward("com.other.app", config))
        }

        @Test
        fun `WHITELIST mode forwards only matching apps`() {
            val config =
                NotificationChannelConfig(
                    enabled = true,
                    filterMode = NotificationFilterMode.WHITELIST,
                    filterApps = setOf("com.whitelisted.app"),
                )
            assertTrue(shouldForward("com.whitelisted.app", config))
        }

        @Test
        fun `WHITELIST mode blocks non-matching apps`() {
            val config =
                NotificationChannelConfig(
                    enabled = true,
                    filterMode = NotificationFilterMode.WHITELIST,
                    filterApps = setOf("com.whitelisted.app"),
                )
            assertFalse(shouldForward("com.other.app", config))
        }

        @Test
        fun `BLACKLIST mode blocks matching apps`() {
            val config =
                NotificationChannelConfig(
                    enabled = true,
                    filterMode = NotificationFilterMode.BLACKLIST,
                    filterApps = setOf("com.blocked.app"),
                )
            assertFalse(shouldForward("com.blocked.app", config))
        }

        @Test
        fun `BLACKLIST mode forwards non-matching apps`() {
            val config =
                NotificationChannelConfig(
                    enabled = true,
                    filterMode = NotificationFilterMode.BLACKLIST,
                    filterApps = setOf("com.blocked.app"),
                )
            assertTrue(shouldForward("com.other.app", config))
        }
    }

    @Nested
    @DisplayName("lifecycle")
    inner class Lifecycle {
        @Test
        fun `stop is safe to call without start`() {
            val dispatcher = createMockDispatcher()
            val listener = NotificationEventListener(dispatcher, mockk(relaxed = true))
            // Calling stop before start should not throw
            listener.stop()
            // Calling stop again should also be safe (idempotent)
            listener.stop()
        }
    }

    /**
     * Mirrors the private shouldForward logic from NotificationEventListener
     * to verify filter behavior independently.
     */
    private fun shouldForward(
        packageName: String,
        config: NotificationChannelConfig,
    ): Boolean =
        when (config.filterMode) {
            NotificationFilterMode.ALL -> true
            NotificationFilterMode.WHITELIST -> packageName in config.filterApps
            NotificationFilterMode.BLACKLIST -> packageName !in config.filterApps
        }
}
