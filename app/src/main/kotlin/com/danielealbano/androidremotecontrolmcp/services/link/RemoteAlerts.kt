package com.danielealbano.androidremotecontrolmcp.services.link

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteContact
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Something on a computer the user should hear about while the app is not on screen. */
data class RemoteAlert(
    val agentId: String,
    /** True when the agent waits for a permission; false when it finished what it was doing. */
    val waiting: Boolean,
    /** What it asks leave for (the tool's name), "" when it merely finished. */
    val what: String = "",
)

/**
 * Decides which changes of an agent are worth a notification: a permission request not seen
 * before, and the end of a turn that was seen running. An agent first heard of while idle says
 * nothing. Pure.
 */
class AlertRules {
    private val running = HashSet<String>()
    private val asked = HashSet<String>()

    fun of(event: RemoteEvent): RemoteAlert? = (event as? RemoteEvent.AgentChanged)?.let { changed(it.agent) }

    private fun changed(agent: RemoteAgent): RemoteAlert? {
        val fresh = agent.pending.firstOrNull { asked.add(agent.id + "/" + it.id) }
        val wasRunning = agent.id in running
        if (agent.status == RUNNING) running += agent.id else running -= agent.id
        return when {
            fresh != null -> RemoteAlert(agent.id, waiting = true, what = fresh.name)
            wasRunning && agent.status != RUNNING && agent.pending.isEmpty() -> RemoteAlert(agent.id, waiting = false)
            else -> null
        }
    }

    private companion object {
        const val RUNNING = "running"
    }
}

/**
 * Notifies about agents that are contacts when they finish or wait for the user, while the app
 * is not on screen; a tap opens that contact's conversation. The words carry the contact's name
 * and the tool's name only: never what the agent wrote, nor anything about the pairing.
 */
@Singleton
class RemoteAlerts
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val characters: CharacterRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val rules = AlertRules()

        @Volatile
        var appVisible = false

        @Synchronized
        fun took(
            computer: PairedComputer,
            event: RemoteEvent,
        ) {
            val alert = rules.of(event) ?: return
            val choice = RemoteContact(computer.id, alert.agentId).choice()
            val contact = characters.characters.value.firstOrNull { it.model == choice }
            if (!appVisible && contact != null) post(alert, contact.id, contact.name)
        }

        /** The tap on a notification: the contact it was about becomes the one on screen. */
        fun opened(intent: Intent?) {
            val contact = intent?.getStringExtra(EXTRA_CONTACT)?.takeIf { it.isNotBlank() } ?: return
            intent.removeExtra(EXTRA_CONTACT)
            scope.launch { characters.setActive(contact) }
        }

        private fun post(
            alert: RemoteAlert,
            contactId: String,
            name: String,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
            val open =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_OPEN_TAB, "chat")
                    .putExtra(EXTRA_CONTACT, contactId)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val text = if (alert.waiting) "在等你批准：${alert.what}" else "做完了，点开看看"
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(name)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(context, contactId.hashCode(), open, flags))
                    .build()
            // One notification per contact: a newer one replaces the older.
            runCatching { manager.notify(contactId.hashCode(), notification) }
        }

        companion object {
            const val EXTRA_CONTACT = "open_contact"
            private const val CHANNEL = "remote_messages"
            private const val CHANNEL_NAME = "电脑上的代理"
        }
    }
