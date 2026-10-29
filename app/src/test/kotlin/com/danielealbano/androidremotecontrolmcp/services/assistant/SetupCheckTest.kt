package com.danielealbano.androidremotecontrolmcp.services.assistant

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SetupSteps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Setup checklist")
class SetupCheckTest {
    @Test
    fun `a fresh install is told to connect a model and switch on accessibility, in that order`() {
        val fresh = SetupState(model = false, accessibility = false, speech = false)
        assertEquals(listOf(SetupStep.MODEL, SetupStep.ACCESSIBILITY), SetupChecklist.missing(fresh))
    }

    @Test
    fun `voice is only offered once the two essentials are done`() {
        assertEquals(listOf(SetupStep.SPEECH), SetupChecklist.missing(SetupState(speech = false)))
        val noModel = SetupState(model = false, speech = false)
        assertEquals(listOf(SetupStep.MODEL), SetupChecklist.missing(noModel))
    }

    @Test
    fun `nothing is shown when everything is set up`() {
        assertTrue(SetupChecklist.missing(SetupState()).isEmpty())
    }

    @Test
    fun `a contact with no model chosen is only ready on a rooted phone whose helper has one`() {
        assertFalse(SetupChecklist.modelReady(ModelChoice.ON_DEVICE, setOf("p1"), helperConfigured = false))
        assertTrue(SetupChecklist.modelReady(ModelChoice.ON_DEVICE, emptySet(), helperConfigured = true))
    }

    @Test
    fun `a chosen provider must still exist`() {
        assertTrue(SetupChecklist.modelReady(ModelChoice("p1", "m"), setOf("p1"), helperConfigured = false))
        assertFalse(SetupChecklist.modelReady(ModelChoice("gone", "m"), setOf("p1"), helperConfigured = true))
    }

    @Test
    fun `each step leads to where it is done`() {
        assertEquals(SettingsRoute.Providers, SetupSteps.pageOf(SetupStep.MODEL))
        assertEquals(SettingsRoute.Chat, SetupSteps.pageOf(SetupStep.SPEECH))
        assertNull(SetupSteps.pageOf(SetupStep.ACCESSIBILITY))
    }
}
