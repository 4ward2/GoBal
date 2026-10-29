package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuthAccessValidator")
class OAuthAccessValidatorTest {
    private val resource = "https://host.example/mcp"
    private lateinit var tokenService: JwtTokenService
    private lateinit var clientRepository: OAuthClientRepository

    private fun client(id: String) =
        OAuthClient(
            clientId = id,
            redirectUris = listOf("https://claude.ai/api/mcp/auth_callback"),
            createdAtMs = 0L,
            lastUsedAtMs = 0L,
        )

    @BeforeEach
    fun setUp() {
        tokenService = mockk()
        clientRepository = mockk(relaxUnitFun = true)
    }

    @Test
    @DisplayName("valid token for registered client passes")
    fun validPasses() =
        runTest {
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", resource)
            coEvery { clientRepository.getClient("c1") } returns client("c1")
            val validator = OAuthAccessValidator(tokenService, clientRepository)
            assertTrue(validator.validate("tok", resource))
        }

    @Test
    @DisplayName("wrong aud rejected")
    fun wrongAudRejected() =
        runTest {
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", "https://other/mcp")
            coEvery { clientRepository.getClient("c1") } returns client("c1")
            val validator = OAuthAccessValidator(tokenService, clientRepository)
            assertFalse(validator.validate("tok", resource))
        }

    @Test
    @DisplayName("revoked client rejected")
    fun revokedRejected() =
        runTest {
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", resource)
            coEvery { clientRepository.getClient("c1") } returns null
            val validator = OAuthAccessValidator(tokenService, clientRepository)
            assertFalse(validator.validate("tok", resource))
        }

    @Test
    @DisplayName("touchLastUsed called once then throttled within window")
    fun touchDebounced() =
        runTest {
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", resource)
            coEvery { clientRepository.getClient("c1") } returns client("c1")
            var now = 1000L
            val validator = OAuthAccessValidator(tokenService, clientRepository, debounceMs = 1000L, nowMs = { now })

            validator.validate("tok", resource)
            validator.validate("tok", resource)
            coVerify(exactly = 1) { clientRepository.touchLastUsed("c1", any()) }

            now += 1000L
            validator.validate("tok", resource)
            coVerify(exactly = 2) { clientRepository.touchLastUsed("c1", any()) }
        }
}
