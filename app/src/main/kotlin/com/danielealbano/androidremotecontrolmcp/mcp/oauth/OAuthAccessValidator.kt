package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Validates an OAuth access token for `/mcp`: signature + type (via [JwtTokenService]), `aud` match
 * against the canonical resource, and the client still being in the registry (instant revocation).
 * On success it updates the client's last-used timestamp, DEBOUNCED so quick successive `/mcp` calls
 * do not write to DataStore on every request. Extracted (not inlined in the server) so it is unit
 * testable and shared by production and the test helper.
 */
class OAuthAccessValidator(
    private val tokenService: JwtTokenService,
    private val clientRepository: OAuthClientRepository,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lastTouched = ConcurrentHashMap<String, Long>()

    suspend fun validate(
        token: String,
        canonicalResource: String,
    ): Boolean {
        val claims = tokenService.verifyAccessToken(token) ?: return false
        val valid =
            OAuthPolicy.resourceMatches(claims.audience, canonicalResource) &&
                clientRepository.getClient(claims.clientId) != null
        if (valid) {
            val now = nowMs()
            val last = lastTouched[claims.clientId]
            if (last == null || now - last >= debounceMs) {
                lastTouched[claims.clientId] = now
                clientRepository.touchLastUsed(claims.clientId, now)
                pruneDebounceMap()
            }
        }
        return valid
    }

    /** Bounds the debounce map so revoked/re-registered clients (each a new id) cannot grow it forever. */
    private suspend fun pruneDebounceMap() {
        if (lastTouched.size <= OAuthPolicy.MAX_OAUTH_CLIENTS) return
        val live = clientRepository.getClients().mapTo(HashSet()) { it.clientId }
        lastTouched.keys.retainAll(live)
        if (lastTouched.size > OAuthPolicy.MAX_OAUTH_CLIENTS) lastTouched.clear()
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MS = 60_000L
    }
}
