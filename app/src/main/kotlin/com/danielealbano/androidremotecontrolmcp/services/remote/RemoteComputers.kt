package com.danielealbano.androidremotecontrolmcp.services.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.chat.AtomicFiles
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.ClientIdentity
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoBackend
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoRpc
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypts what is written to the phone's storage with a key that cannot leave the phone. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable Android Keystore key of its own (not the vault's).
 * Layout: 12-byte IV, then ciphertext and tag.
 */
class KeystoreSealer(
    private val alias: String,
) : Sealer {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        return existing ?: generate()
    }

    private fun generate(): SecretKey {
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec =
            KeyGenParameterSpec
                .Builder(alias, purposes)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}

/** Everything about the paired computers that is written down. The format carries its own version. */
@Serializable
data class ComputersFile(
    val format: Int = FORMAT,
    /** This installation's id towards the computers. */
    val clientId: String = "",
    val computers: List<PairedComputer> = emptyList(),
) {
    companion object {
        const val FORMAT = 1
    }
}

/** The paired computers on disk: one sealed file, written whole. Not part of any backup. */
class ComputersStore(
    private val file: File,
    private val sealer: Sealer,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** This installation's id towards the computers, made once. */
    @Synchronized
    fun clientId(): String {
        val stored = load()
        if (stored.clientId.isNotEmpty()) return stored.clientId
        val fresh = "brooce-" + UUID.randomUUID()
        write(stored.copy(clientId = fresh))
        return fresh
    }

    @Synchronized
    fun write(content: ComputersFile) {
        val sealed = sealer.seal(json.encodeToString(content).toByteArray())
        AtomicFiles.write(file, Base64.getEncoder().encodeToString(sealed))
    }

    /** What is stored; an unreadable file (another phone's, or a wiped key) reads as nothing paired. */
    @Synchronized
    fun load(): ComputersFile {
        val text = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        val sealed = text?.let { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }
        val plain = sealed?.let { runCatching { sealer.open(it).toString(Charsets.UTF_8) }.getOrNull() }
        return plain?.let { runCatching { json.decodeFromString<ComputersFile>(it) }.getOrNull() } ?: ComputersFile()
    }
}

/**
 * The computers this phone is bound to, and the live session with each. The credentials live in
 * a file sealed with a Keystore key; they are never logged, never part of a backup, and the rest
 * of the app only ever sees a computer's name and fingerprint.
 */
@Singleton
class RemoteComputers
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        private val hooks: ChannelHooks,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val store = ComputersStore(File(context.filesDir, FILE), KeystoreSealer(KEY_ALIAS))

        // No ping interval of the socket's own: one fixed timer per connection is what the
        // connection manager's single, adaptive heartbeat replaces.
        private val http = OkHttpClient.Builder().build()
        private val _computers = MutableStateFlow<List<PairedComputer>>(emptyList())
        val computers: StateFlow<List<PairedComputer>> = _computers.asStateFlow()
        private val sessions = ConcurrentHashMap<String, Pair<AgentSession, Job>>()
        private val backends: Map<String, RemoteBackend> by lazy {
            val identity = ClientIdentity(store.clientId(), BuildConfig.VERSION_NAME)
            listOf<RemoteBackend>(PaseoBackend(http, identity, { vpnActive }, hooks)).associateBy { it.id }
        }

        @Volatile
        private var loaded = false

        /** Reads the stored computers once; safe to call often. */
        @Synchronized
        fun load() {
            if (loaded) return
            _computers.value = store.load().computers
            loaded = true
        }

        /** What the scanned or pasted [input] describes, read by whichever backend understands it. */
        fun read(input: String): Result<PairedComputer> {
            val attempts = backends.values.map { it.pairing.read(input, System.currentTimeMillis()) }
            return attempts.firstOrNull { it.isSuccess } ?: attempts.first()
        }

        /** Binds [computer]; one already bound to the same peer is replaced, keeping its handle and name. */
        @Synchronized
        fun add(computer: PairedComputer): PairedComputer {
            load()
            val known = _computers.value
            val same = known.firstOrNull { it.fingerprint == computer.fingerprint && it.backend == computer.backend }
            val name = computer.name.ifBlank { same?.name.orEmpty() }
            val kept = if (same == null) computer else computer.copy(id = same.id, name = name)
            save(_computers.value.filterNot { it.id == kept.id } + kept)
            disconnect(kept.id)
            return kept
        }

        @Synchronized
        fun update(computer: PairedComputer) {
            load()
            save(_computers.value.map { if (it.id == computer.id) computer else it })
            disconnect(computer.id)
        }

        /** Calls the computer what it calls itself, unless it has a name already; the connection stays as it is. */
        @Synchronized
        fun nameIfUnnamed(
            id: String,
            name: String,
        ) {
            load()
            val unnamed = _computers.value.any { it.id == id && it.name.isBlank() }
            if (unnamed) save(_computers.value.map { if (it.id == id) it.copy(name = name) else it })
        }

        /** Unbinds: the credential is deleted and the connection closed. */
        @Synchronized
        fun remove(id: String) {
            load()
            save(_computers.value.filterNot { it.id == id })
            disconnect(id)
        }

        /** The live session with the computer [id], connecting if need be; null when it is not bound. */
        fun session(id: String): AgentSession? {
            load()
            val computer = _computers.value.firstOrNull { it.id == id }
            val backend = computer?.let { backends[it.backend] } ?: return null
            val live =
                sessions.computeIfAbsent(id) {
                    val job = SupervisorJob(scope.coroutineContext[Job])
                    backend.connect(computer, CoroutineScope(scope.coroutineContext + job)) to job
                }
            return live.first
        }

        /** The raw conversation with the computer [id], for the feature clients; null when not bound. */
        fun rpc(id: String): PaseoRpc? = (session(id) as? PaseoSession)?.rpc

        /** True while some VPN app carries the phone's traffic. Asked of the system; no app is looked at. */
        val vpnActive: Boolean
            get() {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
                return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }

        /** Closes the connection to the computer [id]; it stays bound, and is connected again when next asked for. */
        fun disconnect(id: String) {
            sessions.remove(id)?.let { (session, job) ->
                session.close()
                job.cancel()
            }
        }

        private fun save(computers: List<PairedComputer>) {
            store.write(ComputersFile(clientId = store.clientId(), computers = computers))
            _computers.value = computers
        }

        companion object {
            /** Under `files/`; listed in the backup exclusion rules. */
            const val FILE = "remote/computers.bin"
            private const val KEY_ALIAS = "phonemcp_remote_v1"
        }
    }
