package com.danielealbano.androidremotecontrolmcp.utils

import android.content.Intent
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SpecialAccessTest {
    private val first = mockk<Intent>()
    private val second = mockk<Intent>()

    @Test
    fun `the first page the phone has is the one opened`() {
        assertSame(first, SpecialAccess.firstAvailable(listOf(first, second)) { true })
    }

    @Test
    fun `a page the vendor removed is skipped for the next one`() {
        assertSame(second, SpecialAccess.firstAvailable(listOf(first, second)) { it === second })
    }

    @Test
    fun `with no page at all nothing is opened`() {
        assertNull(SpecialAccess.firstAvailable(listOf(first, second)) { false })
    }
}
