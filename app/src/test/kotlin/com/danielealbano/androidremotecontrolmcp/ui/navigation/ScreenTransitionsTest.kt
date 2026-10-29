package com.danielealbano.androidremotecontrolmcp.ui.navigation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ScreenTransitions")
class ScreenTransitionsTest {
    @Test
    fun `a pushed page is drawn over the page it covers`() {
        assertTrue(ScreenTransitions.transform(forward = true).targetContentZIndex > 0f)
    }

    @Test
    fun `on the way back the dismissed page stays on top while it slides away`() {
        assertTrue(ScreenTransitions.transform(forward = false).targetContentZIndex < 0f)
    }
}
