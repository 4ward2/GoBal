package com.danielealbano.androidremotecontrolmcp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderTransfer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

/**
 * Carries the saved providers, keys included, between two phones of the same owner over `adb`.
 * The manifest demands `android.permission.DUMP` of the sender, which only the shell and the
 * system hold: no other app can ask for the keys or plant providers.
 *
 * ```
 * # On the phone that has them: writes <external files>/transfer/providers.json
 * adb shell am broadcast -a com.danielealbano.androidremotecontrolmcp.PROVIDERS_EXPORT \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.debug.ProviderTransferReceiver
 *
 * # On the phone that wants them, after pushing the file to the same place: reads it, then deletes it
 * adb shell am broadcast -a com.danielealbano.androidremotecontrolmcp.PROVIDERS_IMPORT \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.debug.ProviderTransferReceiver
 * ```
 *
 * The file holds keys in the clear: pull it and delete it straight away. Only counts are logged
 * (`MCP:ProviderTransfer`), never names, addresses or keys.
 */
@AndroidEntryPoint
class ProviderTransferReceiver : BroadcastReceiver() {
    @Inject
    lateinit var transfer: ProviderTransfer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action.orEmpty()
        val file = File(context.getExternalFilesDir(DIR), FILE)
        val pending = goAsync()
        scope.launch {
            try {
                val line = withTimeoutOrNull(TIMEOUT_MS) { handle(action, file) } ?: "timed out"
                Log.i(TAG, line)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(
        action: String,
        file: File,
    ): String =
        when {
            action.endsWith(EXPORT) -> {
                file.parentFile?.mkdirs()
                file.writeText(transfer.export())
                "exported to ${file.name}"
            }

            action.endsWith(IMPORT) -> {
                val text = file.takeIf { it.isFile }?.readText()
                // The keys do not stay on shared storage a moment longer than the import takes.
                file.delete()
                val saved = text?.let { transfer.import(it) }
                if (saved == null) "nothing imported: no readable ${file.name}" else "imported $saved providers"
            }

            else -> {
                "unknown action"
            }
        }

    private companion object {
        const val TAG = "MCP:ProviderTransfer"
        const val DIR = "transfer"
        const val FILE = "providers.json"
        const val EXPORT = ".PROVIDERS_EXPORT"
        const val IMPORT = ".PROVIDERS_IMPORT"
        const val TIMEOUT_MS = 15_000L
    }
}
