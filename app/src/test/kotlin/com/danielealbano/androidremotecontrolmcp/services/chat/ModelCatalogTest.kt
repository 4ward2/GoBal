package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ModelCatalog")
class ModelCatalogTest {
    @Test
    fun `ids are read and sorted, entries without an id skipped`() {
        val models = ModelCatalog.parse("""{"data":[{"id":"b-model"},{"id":"a-model"},{"nope":1}]}""")
        assertEquals(listOf("a-model", "b-model"), models.map { it.id })
        assertEquals(emptyList<ModelInfo>(), ModelCatalog.parse("{}"))
        assertEquals(emptyList<ModelInfo>(), ModelCatalog.parse("not json"))
    }

    @Test
    fun `each service's context field is understood`() {
        val body =
            """
            {"data":[
              {"id":"openrouter","context_length":1000000},
              {"id":"groq","context_window":131072},
              {"id":"mistral","max_context_length":"32768"},
              {"id":"vllm","max_model_len":8192.0},
              {"id":"nested","top_provider":{"context_length":65536}},
              {"id":"null-nested","top_provider":{"context_length":null},"context_length":4096},
              {"id":"none"},
              {"id":"zero","context_length":0}
            ]}
            """.trimIndent()
        val windows = ModelCatalog.parse(body).associate { it.id to it.contextWindow }
        assertEquals(1_000_000, windows["openrouter"])
        assertEquals(131_072, windows["groq"])
        assertEquals(32_768, windows["mistral"])
        assertEquals(8_192, windows["vllm"])
        assertEquals(65_536, windows["nested"])
        assertEquals(4_096, windows["null-nested"])
        assertEquals(null, windows["none"])
        assertEquals(null, windows["zero"])
    }

    @Test
    fun `gemini-style lists under models with a models slash prefix are read`() {
        val models = ModelCatalog.parse("""{"models":[{"name":"models/gemini-x","inputTokenLimit":1048576}]}""")
        assertEquals(listOf(ModelInfo("gemini-x", 1_048_576)), models)
    }

    @Test
    fun `speech, embedding and image models are not offered for chat`() {
        val ids =
            listOf(
                "mimo-v2.5",
                "mimo-v2.5-asr",
                "mimo-v2.5-pro",
                "mimo-v2.5-tts",
                "mimo-v2.5-tts-voiceclone",
                "text-embedding-3-small",
                "bge-reranker",
                "whisper-1",
                "dall-e-3",
                "gpt-4o-realtime-preview",
            )
        val chat = ModelCatalog.chatOnly(ids.map { ModelInfo(it) }).map { it.id }
        assertEquals(listOf("mimo-v2.5", "mimo-v2.5-pro"), chat)
    }

    @Test
    fun `models served only on another route are left out for this format`() {
        val body =
            """
            {"data":[
              {"id":"claude-sonnet-5","context_length":1000000,"supported_endpoints":["/messages"]},
              {"id":"deepseek/deepseek-v4-flash","context_length":1000000,
               "supported_endpoints":["/chat/completions","/responses"]},
              {"id":"typesafe/jev","supported_endpoints":["/systemone"]},
              {"id":"unlisted"}
            ]}
            """.trimIndent()
        val models = ModelCatalog.parse(body)
        assertEquals(
            listOf("deepseek/deepseek-v4-flash", "unlisted"),
            ModelCatalog.chatOnly(models, ProviderKind.OPENAI).map { it.id },
        )
        val viaMessages = ModelCatalog.chatOnly(models, ProviderKind.ANTHROPIC).map { it.id }
        assertEquals(listOf("claude-sonnet-5", "unlisted"), viaMessages)
    }

    @Test
    fun `a list of only non-chat models is kept whole rather than emptied`() {
        val only = listOf(ModelInfo("some-tts"))
        assertEquals(only, ModelCatalog.chatOnly(only))
    }
}
