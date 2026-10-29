package com.danielealbano.androidremotecontrolmcp.services.channel.listeners

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.data.model.BluetoothChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelEventFactory
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Emits Event Channel events for Bluetooth ACL connect / disconnect.
 *
 * Requires [Manifest.permission.BLUETOOTH_CONNECT] on API 31+.
 */
class BluetoothEventListener(
    private val eventDispatcher: EventDispatcher,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var currentConfig: BluetoothChannelConfig = BluetoothChannelConfig()

    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null

    fun start(
        config: BluetoothChannelConfig,
        context: Context,
    ) {
        currentConfig = config
        appContext = context.applicationContext

        if (!hasConnectPermission(context)) {
            android.util.Log.w(TAG, "BLUETOOTH_CONNECT not granted; BT events disabled")
            return
        }

        receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    handleIntent(intent)
                }
            }

        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext!!.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext!!.registerReceiver(receiver, filter)
        }
    }

    fun stop() {
        receiver?.let { r ->
            try {
                appContext?.unregisterReceiver(r)
            } catch (_: IllegalArgumentException) {
                // Already unregistered
            }
        }
        receiver = null
        appContext = null
    }

    fun updateConfig(config: BluetoothChannelConfig) {
        currentConfig = config
    }

    private fun handleIntent(intent: Intent) {
        val cfg = currentConfig
        val action = intent.action ?: return
        val device =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

        val ctx = appContext ?: return
        if (!hasConnectPermission(ctx)) return

        val name =
            try {
                device.name
            } catch (_: SecurityException) {
                null
            }
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                null
            } ?: return

        val eventType =
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (!cfg.notifyOnConnected) return
                    "connected"
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (!cfg.notifyOnDisconnected) return
                    "disconnected"
                }
                else -> return
            }

        scope.launch {
            eventDispatcher.dispatch(
                ChannelEventFactory.bluetooth(
                    name = name,
                    address = address,
                    eventType = eventType,
                ),
            )
        }
    }

    private fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "MCP:BluetoothEventListener"
    }
}
