package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.services.shadow.ShadowDisplayClient
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultApprovalGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ActionExecutorImpl with a shadow display target")
class ActionExecutorShadowTargetTest {
    private lateinit var shadow: ShadowDisplayClient
    private lateinit var executor: ActionExecutorImpl

    @BeforeEach
    fun setUp() {
        shadow = mockk()
        coEvery { shadow.request(any(), any()) } returns Result.success(JsonObject(emptyMap()))
        executor = ActionExecutorImpl(mockk(relaxed = true), mockk(relaxed = true), shadow)
        TargetDisplay.set(7)
    }

    @AfterEach
    fun tearDown() {
        TargetDisplay.reset()
        VaultApprovalGate.pending = false
        setServiceInstance(null)
    }

    private fun setServiceInstance(service: McpAccessibilityService?) {
        val field = McpAccessibilityService::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, service)
    }

    @Test
    fun `gestures are refused while an approval card is pending`() =
        runTest {
            setServiceInstance(mockk(relaxed = true))
            VaultApprovalGate.pending = true
            assertTrue(executor.tap(10f, 10f).isFailure)
            assertTrue(executor.swipe(10f, 10f, 20f, 20f, 100L).isFailure)
        }

    @Test
    fun `gestures are refused on the assistant's own active window`() =
        runTest {
            val service = mockk<McpAccessibilityService>(relaxed = true)
            every { service.isOwnWindowActive() } returns true
            setServiceInstance(service)
            assertTrue(executor.tap(10f, 10f).isFailure)
            verify(exactly = 0) { service.dispatchGesture(any(), any(), any()) }
        }

    @Test
    fun `back is injected on the shadow display`() =
        runTest {
            assertTrue(executor.pressBack().isSuccess)
            coVerify {
                shadow.request("key", mapOf("display_id" to JsonPrimitive(7), "keycode" to JsonPrimitive("BACK")))
            }
        }

    @Test
    fun `home recents and shades are refused so the user's screen is not touched`() =
        runTest {
            assertTrue(executor.pressHome().isFailure)
            assertTrue(executor.pressRecents().isFailure)
            assertTrue(executor.openNotifications().isFailure)
            assertTrue(executor.openQuickSettings().isFailure)
            coVerify(exactly = 0) { shadow.request(any(), any()) }
        }

    @Test
    fun `daemon failure surfaces as a failed back press`() =
        runTest {
            coEvery { shadow.request(any(), any()) } returns Result.failure(IllegalStateException("down"))
            assertTrue(executor.pressBack().isFailure)
        }
}
