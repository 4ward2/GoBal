package com.danielealbano.androidremotecontrolmcp.services.provider

import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ModelListMerge")
class ModelListMergeTest {
    private val stored =
        ModelProvider(
            id = "p",
            name = "P",
            baseUrl = "https://api.example.invalid/v1",
            models = listOf("m-2.5", "m-2.5-pro"),
            modelContexts = mapOf("m-2.5" to 128_000),
        )

    @Test
    fun `a model the service lists now appears without anyone typing it in`() {
        val listed = listOf(ModelInfo("m-2.5", 128_000), ModelInfo("m-2.5-pro"), ModelInfo("m-2.6", 1_000_000))
        val merged = checkNotNull(ModelListMerge.merged(stored, listed, emptySet()))
        assertEquals(listOf("m-2.5", "m-2.5-pro", "m-2.6"), merged.models)
        assertEquals(1_000_000, merged.modelContexts["m-2.6"])
    }

    @Test
    fun `a model the service dropped goes, unless a contact still uses it`() {
        val listed = listOf(ModelInfo("m-2.6"))
        assertEquals(listOf("m-2.6"), ModelListMerge.merged(stored, listed, emptySet())?.models)
        val kept = checkNotNull(ModelListMerge.merged(stored, listed, setOf("m-2.5")))
        assertEquals(listOf("m-2.6", "m-2.5"), kept.models)
        assertEquals(128_000, kept.modelContexts["m-2.5"])
    }

    @Test
    fun `nothing is written when nothing changed or the service could not say`() {
        val same = listOf(ModelInfo("m-2.5-pro"), ModelInfo("m-2.5", 128_000))
        assertNull(ModelListMerge.merged(stored, same, emptySet()))
        assertNull(ModelListMerge.merged(stored, emptyList(), emptySet()))
    }
}
