package com.danielealbano.androidremotecontrolmcp.services.provider

import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProviderSetupLogic")
class ProviderSetupLogicTest {
    private val kimi = ProviderPresets.byId("kimi")!!
    private val saved =
        ModelProvider(
            id = "p9",
            name = "我的 Kimi",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://api.moonshot.cn/v1/",
            apiKey = "old",
            models = listOf("moonshot-v1-32k"),
        )

    @Test
    fun `a saved service is found by address even under another name, then by name`() {
        val byUrl = ProviderSetupLogic.withPreset(ProviderDraft(), kimi)
        assertEquals(saved, ProviderSetupLogic.matchExisting(listOf(saved), byUrl))
        val byName = ProviderDraft(name = "我的 kimi", baseUrl = "https://other.example/v1")
        assertEquals(saved, ProviderSetupLogic.matchExisting(listOf(saved), byName))
        assertNull(ProviderSetupLogic.matchExisting(listOf(saved), ProviderDraft()))
    }

    @Test
    fun `updating through a preset keeps the name the user gave the service`() {
        val draft = ProviderSetupLogic.withPreset(ProviderDraft(apiKey = "new"), kimi)
        val setup = ProviderSetup(draft = draft, existing = saved)
        val stored = ProviderSetupLogic.toProvider(setup, newId = "unused")
        assertEquals("我的 Kimi", stored.name)
        assertEquals("p9", stored.id)
        val custom = setup.copy(draft = draft.copy(presetId = null, name = "新名字"))
        assertEquals("新名字", ProviderSetupLogic.toProvider(custom, newId = "unused").name)
    }

    @Test
    fun `choosing custom keeps what was typed`() {
        val typed = ProviderDraft(presetId = "kimi", name = "x", baseUrl = "https://x/v1")
        assertEquals(typed.copy(presetId = null), ProviderSetupLogic.withPreset(typed, null))
    }

    @Test
    fun `the preferred model wins, then the one in use, then the first`() {
        fun of(vararg ids: String) = ids.map { ModelInfo(it) }
        assertEquals("moonshot-v1-8k", ProviderSetupLogic.pickModel(of("a", "moonshot-v1-8k"), kimi, saved))
        assertEquals("moonshot-v1-32k", ProviderSetupLogic.pickModel(of("a", "moonshot-v1-32k"), kimi, saved))
        assertEquals("a", ProviderSetupLogic.pickModel(of("a", "b"), null, null))
        assertEquals("", ProviderSetupLogic.pickModel(emptyList(), null, null))
    }

    @Test
    fun `failures are described without echoing the service's reply`() {
        val raw = "HTTP 401: {\"error\":{\"message\":\"Your api key: ****cdef is invalid\"}}"
        val text = ProviderSetupLogic.describeFailure(raw)
        assertEquals("密钥不对或已经失效（HTTP 401）", text)
        assertFalse(text.contains("cdef"))
        assertEquals("这个地址找不到模型列表，检查一下地址（HTTP 404）", ProviderSetupLogic.describeFailure("HTTP 404: x"))
        assertEquals("服务商返回了错误（HTTP 500），可以稍后再试", ProviderSetupLogic.describeFailure("HTTP 500: x"))
        assertEquals("连不上服务商，检查一下网络或地址", ProviderSetupLogic.describeFailure("Unable to resolve host"))
    }
}
