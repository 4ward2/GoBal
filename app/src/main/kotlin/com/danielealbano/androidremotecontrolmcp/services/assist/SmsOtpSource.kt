package com.danielealbano.androidremotecontrolmcp.services.assist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Watches the SMS inbox and hands the body of every newly received message to [onMessage].
 *
 * Requires `READ_SMS`; when it is not granted [start] returns false and the notification path
 * (the messaging app's notification seen by the notification listener) is the only source.
 * Only messages received after [start] are reported, each at most once.
 */
class SmsOtpSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val onMessage: (body: String) -> Unit,
) {
    private var observer: ContentObserver? = null
    private var reportedMissingPermission = false

    @Volatile
    private var lastSeenId = -1L

    /**
     * Starts observing when `READ_SMS` is granted; returns whether the inbox is being observed.
     * Idempotent and cheap, so it is re-called to pick up a permission granted later.
     */
    fun start(): Boolean {
        val granted = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (granted && observer == null) register()
        if (!granted && !reportedMissingPermission) {
            Log.i(TAG, "READ_SMS not granted; SMS codes come only through notifications")
            reportedMissingPermission = true
        }
        return observer != null
    }

    private fun register() {
        scope.launch(ioDispatcher) { lastSeenId = latestInboxMessage()?.first ?: -1L }
        val created =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scope.launch(ioDispatcher) { reportNewMessage() }
                }
            }
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, created)
        observer = created
        Log.i(TAG, "Watching the SMS inbox for verification codes")
    }

    /** Stops observing. Idempotent. */
    fun stop() {
        observer?.let(context.contentResolver::unregisterContentObserver)
        observer = null
    }

    private fun reportNewMessage() {
        val (id, body) = latestInboxMessage() ?: return
        synchronized(this) {
            if (id <= lastSeenId) return
            lastSeenId = id
        }
        onMessage(body)
    }

    private fun latestInboxMessage(): Pair<Long, String>? =
        try {
            context.contentResolver
                .query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY),
                    null,
                    null,
                    "${Telephony.Sms._ID} DESC LIMIT 1",
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) to (cursor.getString(1) ?: "") else null
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "SMS inbox not readable", e)
            null
        }

    companion object {
        private const val TAG = "MCP:SmsOtpSource"
    }
}
