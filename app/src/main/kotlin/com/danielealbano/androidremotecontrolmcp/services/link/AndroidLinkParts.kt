package com.danielealbano.androidremotecontrolmcp.services.link

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** A partial wake lock: the processor stays awake, the screen does not. Always taken with a time limit. */
class PowerWakeLock(
    context: Context,
    tag: String,
) : WakeLockLike {
    private val lock =
        context
            .getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            .apply { setReferenceCounted(false) }

    override fun acquire(timeoutMs: Long) = lock.acquire(timeoutMs)

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}

/** Follows the phone's default network. Registered with the system only between [start] and [stop]. */
class DefaultNetworkWatch(
    context: Context,
) : NetworkWatch {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _net = MutableStateFlow(current())
    override val net: StateFlow<Net> = _net.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                _net.value = Net(up = true, kind = kindOf(capabilities))
            }

            override fun onLost(network: Network) {
                _net.value = current()
            }
        }

    @Volatile
    private var registered = false

    @Synchronized
    override fun start() {
        if (registered) return
        _net.value = current()
        manager.registerDefaultNetworkCallback(callback)
        registered = true
    }

    @Synchronized
    override fun stop() {
        if (!registered) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun current(): Net {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        return if (capabilities == null) Net(up = false) else Net(up = true, kind = kindOf(capabilities))
    }

    private fun kindOf(capabilities: NetworkCapabilities): String =
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
}

/**
 * One inexact alarm that may fire while the phone dozes. Inexact on purpose: the system is free
 * to fire it together with other apps' alarms, and no special permission is needed.
 */
class AlarmTicker(
    private val context: Context,
) : Ticker {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override fun after(delayMs: Long) {
        val at = SystemClock.elapsedRealtime() + delayMs
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent())
    }

    override fun cancel() = alarms.cancel(intent())

    private fun intent(): PendingIntent {
        val tick = Intent(context, LinkTickReceiver::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, 0, tick, flags)
    }
}

/** The alarm of [AlarmTicker] arriving: the manager does what is due and the phone goes back to sleep. */
@AndroidEntryPoint
class LinkTickReceiver : BroadcastReceiver() {
    @Inject
    lateinit var manager: ConnectionManager

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(TICK_TIMEOUT_MS) { manager.tick() }
            } catch (failed: IllegalStateException) {
                Log.w(TAG, "tick failed: ${failed.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MCP:Link"

        /** Well inside the time a broadcast receiver is given. */
        const val TICK_TIMEOUT_MS = 20_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Shows that the app stays connected by running [OnlineService]. */
class ServiceHost(
    private val context: Context,
) : OnlineHost {
    override fun show(labels: List<String>) = OnlineService.start(context, labels)

    override fun hide() = OnlineService.stop(context)
}
