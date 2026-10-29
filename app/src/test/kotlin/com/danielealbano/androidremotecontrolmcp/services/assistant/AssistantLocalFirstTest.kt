package com.danielealbano.androidremotecontrolmcp.services.assistant

import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderSetupRequests
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRepository
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultRepository
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultSaveRequests
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@DisplayName("AssistantLocalFirst")
class AssistantLocalFirstTest {
    private val repository =
        mockk<VaultRepository>(relaxed = true) {
            every { slots } returns MutableStateFlow(emptyList<VaultSlot>())
        }
    private val requests = VaultSaveRequests(repository)
    private val providers = mockk<ProviderSetupRequests>(relaxed = true)
    private val savedSkills = MutableStateFlow(emptyList<Skill>())
    private val skills =
        mockk<SkillRepository>(relaxed = true) {
            every { skills } returns savedSkills
        }
    private val local = AssistantLocalFirst(repository, requests, providers, skills)

    @Test
    fun `a saved skill's name goes to the phone agent even when it does not sound like a command`() =
        runTest {
            savedSkills.value = listOf(Skill(name = "老样子", steps = listOf(SkillStep.Back)))
            assertEquals(LocalOutcome.Forward(phoneControl = true), local.handle("老样子"))
        }

    @Test
    fun `ordinary talk still goes to the language model`() =
        runTest {
            savedSkills.value = listOf(Skill(name = "老样子", steps = listOf(SkillStep.Back)))
            assertEquals(LocalOutcome.Forward(phoneControl = false), local.handle("今天天气不错"))
        }

    @Test
    fun `a vault sentence is queued for confirmation and not forwarded`() =
        runTest {
            val outcome = local.handle("记住我的手机号 幺三八 一二三四 五六七八")
            assertEquals(LocalOutcome.Handled(message = null), outcome)
            assertEquals(
                "13812345678",
                requests.pending.value
                    ?.draft
                    ?.value,
            )
        }

    @Test
    fun `a secret is refused with a reason and nothing is queued`() =
        runTest {
            val outcome = local.handle("我的银行卡密码是一二三四五六")
            assertNotNull((outcome as LocalOutcome.Handled).message)
            assertNull(requests.pending.value)
        }

    @Test
    fun `a sentence with an API key opens the service card and is not forwarded`() =
        runTest {
            val key = "sk-" + "c3".repeat(16)
            assertEquals(LocalOutcome.Handled(message = null), local.handle("添加服务商 DeepSeek $key"))
            coVerify { providers.offer(match { it.presetId == "deepseek" && it.apiKey == key }) }
            assertNull(requests.pending.value)
        }

    @Test
    fun `commands and questions are forwarded`() =
        runTest {
            assertEquals(LocalOutcome.Forward(phoneControl = true), local.handle("打开美团，帮我找附近评分高的外卖"))
            assertEquals(LocalOutcome.Forward(phoneControl = false), local.handle("今天天气怎么样"))
        }

    @Test
    fun `an unreadable vault still routes known slot names to the vault`() =
        runTest {
            coEvery { repository.load() } throws IOException("broken")
            assertEquals(LocalOutcome.Handled(message = null), local.handle("我的学号是二零二一零一零五"))
        }
}
