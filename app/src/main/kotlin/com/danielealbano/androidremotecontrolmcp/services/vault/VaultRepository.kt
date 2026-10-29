package com.danielealbano.androidremotecontrolmcp.services.vault

import android.content.Context
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

interface VaultRepository {
    /** Slot metadata for the UI and `vault_list_slots`; never contains values. */
    val slots: StateFlow<List<VaultSlot>>

    val fillLog: StateFlow<List<VaultFillRecord>>

    /** Current redactor over every non-credential value. */
    val redactor: StateFlow<ContentRedactor>

    /** @throws IOException when a vault file exists but cannot be decrypted. */
    suspend fun load()

    suspend fun upsert(entry: VaultEntry): Result<Unit>

    suspend fun delete(id: String)

    /** Decrypts one slot for filling or editing. Credentials are read from disk on each call. */
    suspend fun reveal(id: String): VaultEntry?

    suspend fun isApproved(
        id: String,
        packageName: String,
    ): Boolean

    suspend fun rememberApproval(
        id: String,
        packageName: String,
    )

    suspend fun recordFill(record: VaultFillRecord)

    /** Deletes every slot, approval and log entry (the only way out of an undecryptable vault). */
    suspend fun reset()
}

@Serializable
internal data class VaultProfile(
    val entries: List<VaultEntry> = emptyList(),
    val approvals: List<String> = emptyList(),
    val log: List<VaultFillRecord> = emptyList(),
) {
    fun withEntry(entry: VaultEntry): VaultProfile {
        val merged = entries.filterNot { it.id == entry.id } + entry
        return copy(entries = merged.sortedWith(compareBy({ it.category.ordinal }, { it.id })))
    }

    /** Editing or deleting a slot invalidates earlier "always allow in this app" answers. */
    fun withoutApprovals(id: String) = copy(approvals = approvals.filterNot { it.startsWith("$id|") })
}

@Serializable
internal data class VaultSecrets(
    val values: Map<String, String> = emptyMap(),
)

/** One JSON document stored encrypted, replaced atomically on every write. */
internal class EncryptedJsonFile<T>(
    private val file: File,
    private val cipher: VaultCipher,
    private val serializer: KSerializer<T>,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun read(): T? =
        withContext(ioDispatcher) {
            if (!file.exists()) return@withContext null
            // Key lost after a restore, or tampered: fail loudly and keep the file for inspection
            // rather than silently overwriting the user's data on the next save.
            val plain =
                runCatching { cipher.decrypt(file.readBytes()) }.getOrElse { e ->
                    Log.e(TAG, "cannot decrypt ${file.name}: ${e.javaClass.simpleName}")
                    throw IOException("保险箱文件无法解密（${file.name}）", e)
                }
            json.decodeFromString(serializer, String(plain, Charsets.UTF_8))
        }

    suspend fun write(value: T) {
        withContext(ioDispatcher) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            val bytes = cipher.encrypt(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            // fsync before the rename so a power loss cannot leave a truncated, undecryptable file.
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    suspend fun delete() {
        withContext(ioDispatcher) {
            file.delete()
            File(file.parentFile, "${file.name}.tmp").delete()
        }
    }

    private companion object {
        const val TAG = "MCP:Vault"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** The observable, value-free views of the vault, rebuilt from a profile snapshot. */
internal class VaultViews {
    val slots = MutableStateFlow<List<VaultSlot>>(emptyList())
    val fillLog = MutableStateFlow<List<VaultFillRecord>>(emptyList())
    val redactor = MutableStateFlow<ContentRedactor>(ContentRedactor.NONE)

    fun publish(profile: VaultProfile) {
        slots.value = profile.entries.map { it.toSlot() }
        fillLog.value = profile.log
        redactor.value =
            VaultRedactor(
                profile.entries
                    .filter { it.category != VaultCategory.CREDENTIAL && it.value.isNotEmpty() }
                    .associate { it.id to it.value },
            )
    }
}

/**
 * Two encrypted files in app-private storage:
 *  - `profile.bin`: slot metadata, non-credential values, per-app approvals and the fill log.
 *    Kept in memory (the redactor needs the values to scrub screen text).
 *  - `secrets.bin`: credential values only. Decrypted on demand, never cached, so a password
 *    exists in memory only for the instant it is written into a password field.
 */
class FileVaultRepository(
    dir: File,
    cipher: VaultCipher,
    ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : VaultRepository {
    private val mutex = Mutex()
    private val profileFile =
        EncryptedJsonFile(File(dir, "profile.bin"), cipher, VaultProfile.serializer(), ioDispatcher)
    private val secretsFile =
        EncryptedJsonFile(File(dir, "secrets.bin"), cipher, VaultSecrets.serializer(), ioDispatcher)
    private val views = VaultViews()
    private var profile: VaultProfile? = null

    override val slots: StateFlow<List<VaultSlot>> = views.slots.asStateFlow()
    override val fillLog: StateFlow<List<VaultFillRecord>> = views.fillLog.asStateFlow()
    override val redactor: StateFlow<ContentRedactor> = views.redactor.asStateFlow()

    override suspend fun load() {
        mutex.withLock { current() }
    }

    override suspend fun upsert(entry: VaultEntry): Result<Unit> =
        mutex.withLock {
            val base = current()
            VaultValidation.validate(entry)?.let { return@withLock Result.failure(IllegalArgumentException(it)) }
            val stamped = entry.copy(updatedAt = clock())
            val secrets = secretsFile.read()?.values.orEmpty()
            val isCredential = stamped.category == VaultCategory.CREDENTIAL
            // Credentials keep only metadata in the profile; any other category drops a stale secret.
            if (isCredential) {
                secretsFile.write(VaultSecrets(secrets + (stamped.id to stamped.value)))
            } else if (stamped.id in secrets) {
                secretsFile.write(VaultSecrets(secrets - stamped.id))
            }
            val stored = if (isCredential) stamped.copy(value = "") else stamped
            save(base.withEntry(stored).withoutApprovals(entry.id))
            Result.success(Unit)
        }

    override suspend fun delete(id: String) {
        mutex.withLock {
            val base = current()
            val secrets = secretsFile.read()?.values.orEmpty()
            if (id in secrets) secretsFile.write(VaultSecrets(secrets - id))
            save(base.copy(entries = base.entries.filterNot { it.id == id }).withoutApprovals(id))
        }
    }

    override suspend fun reveal(id: String): VaultEntry? =
        mutex.withLock {
            val entry = current().entries.firstOrNull { it.id == id }
            if (entry == null || entry.category != VaultCategory.CREDENTIAL) {
                entry
            } else {
                val secrets = secretsFile.read()?.values.orEmpty()
                secrets[id]?.let { entry.copy(value = it) }
            }
        }

    override suspend fun isApproved(
        id: String,
        packageName: String,
    ): Boolean = mutex.withLock { "$id|$packageName" in current().approvals }

    override suspend fun rememberApproval(
        id: String,
        packageName: String,
    ) {
        mutex.withLock {
            val base = current()
            val key = "$id|$packageName"
            if (key !in base.approvals) save(base.copy(approvals = base.approvals + key))
        }
    }

    override suspend fun recordFill(record: VaultFillRecord) {
        mutex.withLock {
            val base = current()
            save(base.copy(log = (listOf(record) + base.log).take(MAX_LOG)))
        }
    }

    override suspend fun reset() {
        mutex.withLock {
            profileFile.delete()
            secretsFile.delete()
            profile = VaultProfile()
            views.publish(VaultProfile())
        }
    }

    /** Loaded profile; reads it from disk on first use. Call with [mutex] held. */
    private suspend fun current(): VaultProfile =
        profile ?: (profileFile.read() ?: VaultProfile()).also {
            profile = it
            views.publish(it)
        }

    /** Persists and publishes [next]. Call with [mutex] held. */
    private suspend fun save(next: VaultProfile) {
        profileFile.write(next)
        profile = next
        views.publish(next)
    }

    private companion object {
        const val MAX_LOG = 100
    }
}

@Singleton
class AndroidVaultRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        cipher: KeystoreVaultCipher,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : VaultRepository by FileVaultRepository(File(context.filesDir, "vault"), cipher, ioDispatcher)
