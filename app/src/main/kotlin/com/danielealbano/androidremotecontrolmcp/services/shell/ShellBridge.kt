package com.danielealbano.androidremotecontrolmcp.services.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone looks like, as far as reaching its own wireless debugging goes. */
data class ShellState(
    /** Notifications are allowed: the pairing code is typed into one. */
    val notifications: Boolean = false,
    /** Developer options are on. */
    val developer: Boolean = false,
    /** The phone is on Wi-Fi: wireless debugging cannot be switched on without it. */
    val wifi: Boolean = false,
    /** Wireless debugging is on. */
    val wireless: Boolean = false,
    /** The phone has been taught this app's key. */
    val paired: Boolean = false,
)

/** The steps of setting it up, in the order the user has to do them. */
enum class ShellStep {
    NOTIFICATIONS,
    DEVELOPER,
    WIFI,
    WIRELESS,
    PAIR,
    READY,
}

/** Which step is open. Pure. */
object ShellChecklist {
    fun next(state: ShellState): ShellStep =
        when {
            // Once paired, the notification is no longer needed.
            state.paired && state.wireless -> ShellStep.READY

            !state.notifications && !state.paired -> ShellStep.NOTIFICATIONS

            !state.developer -> ShellStep.DEVELOPER

            !state.wifi -> ShellStep.WIFI

            !state.wireless -> ShellStep.WIRELESS

            else -> ShellStep.PAIR
        }

    /** What `id` printed says the command ran as the shell user. */
    fun isShell(idOutput: String): Boolean = idOutput.trimStart().startsWith("uid=2000(")
}

/** Finds the ports wireless debugging announces on the local network (they change every time). */
class AdbDiscovery
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** Emits the port of [type] on this phone whenever it appears, and null when it goes away. */
        fun ports(type: String): Flow<Int?> =
            callbackFlow {
                val manager = context.getSystemService(NsdManager::class.java)
                var current: String? = null
                val listener =
                    object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) = Unit

                        override fun onDiscoveryStopped(serviceType: String) = Unit

                        override fun onStartDiscoveryFailed(
                            serviceType: String,
                            errorCode: Int,
                        ) {
                            Log.w(TAG, "discovery of $serviceType failed: $errorCode")
                        }

                        override fun onStopDiscoveryFailed(
                            serviceType: String,
                            errorCode: Int,
                        ) = Unit

                        override fun onServiceFound(info: NsdServiceInfo) {
                            resolve(manager, info) { name, port ->
                                current = name
                                trySend(port)
                            }
                        }

                        override fun onServiceLost(info: NsdServiceInfo) {
                            if (info.serviceName == current) trySend(null)
                        }
                    }
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
            }

        /** The first port of [type] seen within [timeoutMs], or null. */
        suspend fun find(
            type: String,
            timeoutMs: Long,
        ): Int? = withTimeoutOrNull(timeoutMs) { ports(type).filterNotNull().first() }

        // The one-shot resolve is deprecated from API 34 in favour of a callback registration that
        // API 33 (this app's minimum) does not have.
        @Suppress("DEPRECATION")
        private fun resolve(
            manager: NsdManager,
            info: NsdServiceInfo,
            onOwn: (String, Int) -> Unit,
        ) {
            val listener =
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) = Unit

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        // Other phones on the same Wi-Fi announce the same service: only ours counts.
                        val own = isOwnAddress(serviceInfo.host?.hostAddress)
                        if (own) onOwn(serviceInfo.serviceName, serviceInfo.port)
                    }
                }
            runCatching { manager.resolveService(info, listener) }
        }

        private fun isOwnAddress(address: String?): Boolean =
            address != null &&
                NetworkInterface.getNetworkInterfaces().asSequence().any { net ->
                    net.inetAddresses.asSequence().any { it.hostAddress == address }
                }

        companion object {
            const val TLS_CONNECT = "_adb-tls-connect._tcp"
            const val TLS_PAIRING = "_adb-tls-pairing._tcp"
            private const val TAG = "MCP:Shell"
        }
    }

/**
 * Runs commands as the shell user on a phone without root, by being an adb client of the phone's
 * own wireless debugging. The user switches wireless debugging on and pairs once, by hand; this
 * class cannot do either for them.
 */
@Singleton
class ShellBridge
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val identity: AdbIdentity,
        private val discovery: AdbDiscovery,
    ) {
        private val io: CoroutineDispatcher = Dispatchers.IO
        private val oneAtATime = Mutex()
        private val pairedMark get() = File(File(context.filesDir, AdbIdentity.DIR), "paired")

        fun state(): ShellState {
            val resolver = context.contentResolver
            val notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            return ShellState(
                notifications = notifications == PackageManager.PERMISSION_GRANTED,
                developer = Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
                wifi = onWifi(),
                wireless = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1,
                paired = pairedMark.isFile,
            )
        }

        /** Tries the six digits against the pairing page listening on [port]. */
        suspend fun pair(
            port: Int,
            code: String,
        ): PairOutcome =
            withContext(io) {
                val outcome = oneAtATime.withLock { AdbPairingClient(port, code, identity).pair() }
                if (outcome == PairOutcome.PAIRED) {
                    pairedMark.parentFile?.mkdirs()
                    runCatching { pairedMark.writeText("1") }
                }
                Log.i(TAG, "pair: $outcome")
                outcome
            }

        /** Runs [command] as the shell user. Fails when wireless debugging is off or the key is unknown. */
        suspend fun run(command: String): Result<String> =
            withContext(io) {
                oneAtATime.withLock {
                    runCatching {
                        val found = discovery.find(AdbDiscovery.TLS_CONNECT, FIND_MS)
                        val port = found ?: error("wireless debugging not found")
                        AdbConnection(port, identity).use {
                            it.connect()
                            it.shell(command)
                        }
                    }.onFailure {
                        if (it is NotPairedException) pairedMark.delete()
                        Log.w(TAG, "run failed: ${it.javaClass.simpleName}")
                    }
                }
            }

        private fun onWifi(): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        private companion object {
            const val TAG = "MCP:Shell"
            const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
            const val FIND_MS = 8_000L
        }
    }
