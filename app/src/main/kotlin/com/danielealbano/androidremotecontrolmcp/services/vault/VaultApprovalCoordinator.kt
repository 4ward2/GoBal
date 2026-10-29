package com.danielealbano.androidremotecontrolmcp.services.vault

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import com.danielealbano.androidremotecontrolmcp.ui.VaultApprovalActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class VaultApprovalRequest(
    val id: String,
    val slotLabel: String,
    val packageName: String,
    val appLabel: String,
    val sensitive: Boolean,
    val rememberable: Boolean,
)

data class VaultApprovalResult(
    val approved: Boolean,
    val remember: Boolean,
)

/**
 * Process-wide "an approval card is on screen" flag. While set, every tool-driven gesture is
 * refused, so the agent cannot tap "允许" on its own request.
 */
object VaultApprovalGate {
    @Volatile
    var pending: Boolean = false
        internal set
}

/** Asks the user on the phone before a vault value is written into another app. */
interface VaultApprovalCoordinator {
    /** The request currently shown to the user, or null. */
    val pending: StateFlow<VaultApprovalRequest?>

    suspend fun request(
        slotLabel: String,
        packageName: String,
        appLabel: String,
        sensitive: Boolean,
        rememberable: Boolean,
    ): VaultApprovalResult

    fun complete(
        requestId: String,
        result: VaultApprovalResult,
    )
}

/**
 * One request at a time; unanswered requests are denied after [TIMEOUT_MS]. The approval
 * screen always opens on the physical display, even while the agent works on a shadow one.
 */
@Singleton
class VaultApprovalCoordinatorImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : VaultApprovalCoordinator {
        private val serial = Mutex()
        private val _pending = MutableStateFlow<VaultApprovalRequest?>(null)
        override val pending: StateFlow<VaultApprovalRequest?> = _pending.asStateFlow()

        @Volatile
        private var waiter: Pair<String, CompletableDeferred<VaultApprovalResult>>? = null

        override suspend fun request(
            slotLabel: String,
            packageName: String,
            appLabel: String,
            sensitive: Boolean,
            rememberable: Boolean,
        ): VaultApprovalResult =
            serial.withLock {
                val req =
                    VaultApprovalRequest(
                        id = UUID.randomUUID().toString(),
                        slotLabel = slotLabel,
                        packageName = packageName,
                        appLabel = appLabel,
                        sensitive = sensitive,
                        rememberable = rememberable,
                    )
                val deferred = CompletableDeferred<VaultApprovalResult>()
                waiter = req.id to deferred
                _pending.value = req
                VaultApprovalGate.pending = true
                try {
                    launchScreen()
                    withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } ?: DENIED
                } finally {
                    waiter = null
                    _pending.value = null
                    VaultApprovalGate.pending = false
                }
            }

        override fun complete(
            requestId: String,
            result: VaultApprovalResult,
        ) {
            val current = waiter ?: return
            if (current.first == requestId) current.second.complete(result)
        }

        private fun launchScreen() {
            val intent =
                Intent(context, VaultApprovalActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            try {
                context.startActivity(intent, options.toBundle())
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                Log.e(TAG, "cannot show vault approval: ${e.message}")
                waiter?.second?.complete(DENIED)
            }
        }

        companion object {
            private const val TAG = "MCP:VaultApproval"
            const val TIMEOUT_MS = 60_000L
            val DENIED = VaultApprovalResult(approved = false, remember = false)
        }
    }
