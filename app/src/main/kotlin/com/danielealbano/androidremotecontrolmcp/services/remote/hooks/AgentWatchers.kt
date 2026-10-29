package com.danielealbano.androidremotecontrolmcp.services.remote.hooks

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.link.RemoteAlerts
import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import javax.inject.Inject
import javax.inject.Singleton

/** Everything that follows the agents of bound computers while nobody looks: notifications, auto-resume, goals. */
@Singleton
class AgentWatchers
    @Inject
    constructor(
        private val alerts: RemoteAlerts,
        private val resume: AutoResume,
        private val goals: GoalRunner,
    ) {
        /** Whether the app is on screen, for the notifications. */
        var appVisible: Boolean
            get() = alerts.appVisible
            set(value) {
                alerts.appVisible = value
            }

        fun took(
            computer: PairedComputer,
            event: RemoteEvent,
        ) {
            val agent = (event as? RemoteEvent.AgentChanged)?.agent
            if (agent != null) Log.d(TAG, "agent ${agent.id.take(ID_CHARS)} ${agent.status}")
            alerts.took(computer, event)
            resume.took(computer, event)
            goals.took(computer, event)
        }

        private companion object {
            const val TAG = "MCP:Watch"
            const val ID_CHARS = 8
        }
    }
