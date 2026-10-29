package com.danielealbano.androidremotecontrolmcp.services.channel.listeners

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelEventFactory
import com.danielealbano.androidremotecontrolmcp.data.model.WifiChannelConfig
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WifiEventListener(
    private val eventDispatcher: EventDispatcher,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var currentConfig: WifiChannelConfig = WifiChannelConfig()
    private val previouslySeen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var connectedSsid: String? = null

    private var appContext: Context? = null
    private var scanReceiver: BroadcastReceiver? = null
    private var networkStateReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var scanJob: Job? = null
    private var wifiManager: WifiManager? = null
    private var connectivityManager: ConnectivityManager? = null

    fun start(
        config: WifiChannelConfig,
        context: Context,
    ) {
        currentConfig = config
        appContext = context.applicationContext
        wifiManager = context.getSystemService(WifiManager::class.java)
        connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        // Seed current association so disconnect can fire even if transportInfo is null on start.
        seedConnectedSsid()
        Logger.i(TAG, "start ssids=${config.ssids} seeded=$connectedSsid")

        registerScanReceiver()
        registerNetworkStateReceiver()
        registerNetworkCallback()

        // Periodic scan trigger (throttled by Android: ~4/2min foreground, ~1/30min background)
        // WifiManager.startScan() is deprecated since API 28 but there is NO non-deprecated
        // replacement for triggering WiFi scans. Android provides no successor API — apps must
        // either use startScan() or rely solely on passive scan results from the system.
        // This @Suppress is genuinely unavoidable per CLAUDE.md linting suppression rules.
        // Scan-based events are best-effort; connection events are real-time and not deprecated.
        scanJob =
            scope.launch {
                while (isActive) {
                    @Suppress("DEPRECATION")
                    wifiManager?.startScan()
                    delay(SCAN_INTERVAL_MS)
                }
            }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        scanReceiver?.let { receiver ->
            try {
                appContext?.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered
            }
        }
        networkStateReceiver?.let { receiver ->
            try {
                appContext?.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered
            }
        }
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        scanReceiver = null
        networkStateReceiver = null
        networkCallback = null
        appContext = null
        previouslySeen.clear()
        connectedSsid = null
    }

    fun updateConfig(config: WifiChannelConfig) {
        currentConfig = config
    }

    private fun registerScanReceiver() {
        scanReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    handleScanResults()
                }
            }
        try {
            registerExported(scanReceiver!!, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to register scan receiver: ${e.message}")
            scanReceiver = null
        }
    }

    /**
     * OEM Wi-Fi toggles (e.g. `svc wifi disable`) sometimes skip [ConnectivityManager.NetworkCallback.onLost].
     * NETWORK_STATE_CHANGED remains the most reliable connect/disconnect signal.
     */
    private fun registerNetworkStateReceiver() {
        networkStateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
                    @Suppress("DEPRECATION")
                    val info =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
                        } ?: return
                    when (info.detailedState) {
                        NetworkInfo.DetailedState.CONNECTED -> onWifiAssociated()
                        NetworkInfo.DetailedState.DISCONNECTED,
                        NetworkInfo.DetailedState.FAILED,
                        NetworkInfo.DetailedState.IDLE,
                        -> onWifiDisassociated()
                        else -> Unit
                    }
                }
            }
        try {
            registerExported(
                networkStateReceiver!!,
                IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to register network-state receiver: ${e.message}")
            networkStateReceiver = null
        }
    }

    private fun registerNetworkCallback() {
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
        networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) {
                    onWifiAssociated(resolveWifiInfo(caps))
                }

                override fun onLost(network: Network) {
                    onWifiDisassociated()
                }
            }
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to register network callback: ${e.message}")
            networkCallback = null
        }
    }

    private fun registerExported(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
    }

    private fun onWifiAssociated(wifiInfo: WifiInfo? = null) {
        val info = wifiInfo ?: currentWifiInfo() ?: return
        val ssid = normalizeSsid(info.ssid) ?: return
        if (ssid !in currentConfig.ssids || connectedSsid == ssid) return
        connectedSsid = ssid
        Logger.i(TAG, "wifi connected ssid=$ssid")
        if (currentConfig.notifyOnConnected) {
            scope.launch {
                eventDispatcher.dispatch(
                    ChannelEventFactory.wifi(ssid, "connected", info.bssid),
                )
            }
        }
    }

    private fun onWifiDisassociated() {
        val ssid = connectedSsid ?: return
        connectedSsid = null
        Logger.i(TAG, "wifi disconnected ssid=$ssid")
        if (ssid in currentConfig.ssids && currentConfig.notifyOnDisconnected) {
            scope.launch {
                eventDispatcher.dispatch(
                    ChannelEventFactory.wifi(ssid, "disconnected", null),
                )
            }
        }
    }

    /**
     * Android 16+ often leaves [NetworkCapabilities.transportInfo] null for Wi-Fi.
     * Fall back to [WifiManager.connectionInfo] so connect/disconnect events still fire.
     */
    private fun resolveWifiInfo(caps: NetworkCapabilities): WifiInfo? {
        (caps.transportInfo as? WifiInfo)?.let { return it }
        return currentWifiInfo()
    }

    private fun currentWifiInfo(): WifiInfo? {
        @Suppress("DEPRECATION")
        return try {
            wifiManager?.connectionInfo
        } catch (_: SecurityException) {
            null
        }
    }

    private fun seedConnectedSsid() {
        connectedSsid = normalizeSsid(currentWifiInfo()?.ssid)
    }

    private fun normalizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val ssid = raw.removeSurrounding("\"")
        if (ssid.isEmpty() || ssid == UNKNOWN_SSID || ssid.equals("unknown ssid", ignoreCase = true)) {
            return null
        }
        return ssid
    }

    @Suppress("DEPRECATION") // ScanResult.SSID deprecated API 33; getWifiSsid() returns WifiSsid
    // object not directly comparable to String. No non-deprecated String SSID accessor exists.
    private fun handleScanResults() {
        val cfg = currentConfig
        val results =
            try {
                wifiManager?.scanResults ?: return
            } catch (e: SecurityException) {
                // ACCESS_FINE_LOCATION / NEARBY_WIFI_DEVICES may have been revoked at runtime.
                Logger.w(TAG, "Wi-Fi scan results unavailable (permission denied): ${e.message}")
                return
            }
        val currentSsids =
            results
                .mapNotNull { it.SSID }
                .filter { it in cfg.ssids }
                .toSet()

        // Discovered
        if (cfg.notifyOnDiscovered) {
            for (ssid in currentSsids - previouslySeen) {
                val bssid = results.firstOrNull { it.SSID == ssid }?.BSSID
                scope.launch {
                    eventDispatcher.dispatch(ChannelEventFactory.wifi(ssid, "discovered", bssid))
                }
            }
        }

        // Lost
        if (cfg.notifyOnLost) {
            for (ssid in previouslySeen - currentSsids) {
                scope.launch {
                    eventDispatcher.dispatch(ChannelEventFactory.wifi(ssid, "lost", null))
                }
            }
        }

        previouslySeen.clear()
        previouslySeen.addAll(currentSsids)
    }

    companion object {
        private const val TAG = "MCP:WifiEventListener"
        private const val SCAN_INTERVAL_MS = 120_000L // 2 minutes
        private const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
