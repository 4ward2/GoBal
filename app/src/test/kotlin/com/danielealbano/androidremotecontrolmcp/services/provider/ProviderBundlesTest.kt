package com.danielealbano.androidremotecontrolmcp.services.provider

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProviderBundlesTest {
    private val online =
        ModelProvider(
            id = "p1",
            name = "One",
            baseUrl = "https://one.example/v1",
            apiKey = "k",
            models = listOf("m"),
        )
    private val local =
        ModelProvider(id = LocalModels.PROVIDER_ID, name = "local", baseUrl = "http://127.0.0.1:8093/v1")

    @Test
    fun `a bundle survives being written and read back`() {
        val bundle = ProviderBundles.portable(listOf(online), ModelChoice("p1", "m"))
        assertEquals(bundle, ProviderBundles.decode(ProviderBundles.encode(bundle)))
    }

    @Test
    fun `the phone's own model stays behind, and so does a choice that points at it`() {
        val bundle = ProviderBundles.portable(listOf(online, local), ModelChoice(LocalModels.PROVIDER_ID, "glm"))
        assertEquals(listOf(online), bundle.providers)
        assertEquals(ModelChoice(), bundle.assistant)
    }

    @Test
    fun `text that is no bundle, or one from a later format, is refused`() {
        assertNull(ProviderBundles.decode("not json"))
        assertNull(ProviderBundles.decode("""{"format":2,"providers":[]}"""))
    }
}
