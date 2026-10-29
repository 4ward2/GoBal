package com.danielealbano.androidremotecontrolmcp.services.im.weixin

import com.danielealbano.androidremotecontrolmcp.services.chat.AtomicFiles
import com.danielealbano.androidremotecontrolmcp.services.remote.Sealer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * The Weixin login this phone holds, and what the user allowed it. The token is the credential:
 * whoever has it can read and answer the account's bot messages. It is kept sealed, never logged
 * and never shown; the user sees only whether a login exists.
 */
@Serializable
data class WeixinAccount(
    val token: String = "",
    val botId: String = "",
    /** The service's address for this account; "" for the default one. */
    val baseUrl: String = "",
    /** The Weixin user who scanned the login code. Messages from anyone else are dropped unread. */
    val ownerId: String = "",
    /** Where the long poll left off. */
    val cursor: String = "",
    /** The token the owner's last message carried; it goes back with every answer. */
    val contextToken: String = "",
    /** The user's switch: a message from Weixin may make the assistant operate this phone. */
    val mayOperate: Boolean = false,
    /** The channel is switched on. */
    val enabled: Boolean = true,
) {
    val loggedIn: Boolean get() = token.isNotEmpty() && ownerId.isNotEmpty()
}

/** The account on disk: one sealed file, written whole. Not part of any backup. */
class WeixinStore(
    private val file: File,
    private val sealer: Sealer,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): WeixinAccount {
        val text = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        val sealed = text?.let { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }
        val plain = sealed?.let { runCatching { sealer.open(it).toString(Charsets.UTF_8) }.getOrNull() }
        return plain?.let { runCatching { json.decodeFromString<WeixinAccount>(it) }.getOrNull() } ?: WeixinAccount()
    }

    @Synchronized
    fun save(account: WeixinAccount) {
        val sealed = sealer.seal(json.encodeToString(account).toByteArray())
        AtomicFiles.write(file, Base64.getEncoder().encodeToString(sealed))
    }

    /** Logging out: the credential is deleted from the phone. */
    @Synchronized
    fun clear() {
        file.delete()
    }
}
