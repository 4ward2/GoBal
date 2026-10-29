package com.danielealbano.androidremotecontrolmcp.services.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShellChecklistTest {
    private val ready = ShellState(notifications = true, developer = true, wifi = true, wireless = true, paired = true)

    @Test
    fun `the steps open in the order the user has to do them`() {
        assertEquals(ShellStep.NOTIFICATIONS, ShellChecklist.next(ShellState()))
        assertEquals(ShellStep.DEVELOPER, ShellChecklist.next(ShellState(notifications = true)))
        assertEquals(ShellStep.WIFI, ShellChecklist.next(ShellState(notifications = true, developer = true)))
        assertEquals(ShellStep.WIRELESS, ShellChecklist.next(ready.copy(wireless = false, paired = false)))
        assertEquals(ShellStep.PAIR, ShellChecklist.next(ready.copy(paired = false)))
        assertEquals(ShellStep.READY, ShellChecklist.next(ready))
    }

    @Test
    fun `after a reboot only wireless debugging is asked for again`() {
        assertEquals(ShellStep.WIRELESS, ShellChecklist.next(ready.copy(wireless = false)))
        assertEquals(ShellStep.WIFI, ShellChecklist.next(ready.copy(wireless = false, wifi = false)))
    }

    @Test
    fun `a paired phone is not asked for notifications again`() {
        assertEquals(ShellStep.READY, ShellChecklist.next(ready.copy(notifications = false)))
    }

    @Test
    fun `only the shell user's id counts as working`() {
        assertTrue(ShellChecklist.isShell("uid=2000(shell) gid=2000(shell) groups=2000(shell)\n"))
        assertFalse(ShellChecklist.isShell("uid=10192(u0_a192) gid=10192(u0_a192)"))
        assertFalse(ShellChecklist.isShell(""))
    }

    @Test
    fun `a code is six digits however it was typed`() {
        assertEquals("123456", PairNotice.code("123456"))
        assertEquals("123456", PairNotice.code(" 123 456 "))
        assertNull(PairNotice.code("12345"))
        assertNull(PairNotice.code("1234567"))
        assertNull(PairNotice.code(null))
    }

    @Test
    fun `the notification asks for the code only while the page is up`() {
        assertTrue(PairNotice.asksForCode(PairPhase.CODE_WANTED))
        assertTrue(PairNotice.asksForCode(PairPhase.WRONG_CODE))
        assertFalse(PairNotice.asksForCode(PairPhase.SEARCHING))
        assertFalse(PairNotice.asksForCode(PairPhase.DONE))
    }
}
