package com.danielealbano.androidremotecontrolmcp.services.link

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.AgentWatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The user's switch for staying connected while the app is not on screen. On unless switched off. */
@Singleton
class LinkSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val background: Flow<Boolean> = dataStore.data.map { it[KEY] ?: true }

        suspend fun setBackground(on: Boolean) {
            dataStore.edit { it[KEY] = on }
        }

        private companion object {
            val KEY = booleanPreferencesKey("link_background_online")
        }
    }

/** The connection to one paired computer, as the manager keeps it. */
class ComputerLink(
    private val computer: PairedComputer,
    private val computers: RemoteComputers,
    private val scope: CoroutineScope,
    private val onEvent: (PairedComputer, RemoteEvent) -> Unit,
) : ManagedLink {
    override val id: String = "computer:" + computer.id
    override val label: String = computer.name.ifBlank { "电脑" }
    private val _online = MutableStateFlow(false)
    override val online: StateFlow<Boolean> = _online.asStateFlow()
    private var following: Job? = null

    override fun open() {
        // The session is made anew when the computer's record changes (a route, the relay):
        // whichever is current is the one followed.
        val record = computers.computers.map { all -> all.firstOrNull { it.id == computer.id } }.distinctUntilChanged()
        following =
            scope.launch {
                record.collectLatest {
                    val session = computers.session(computer.id) ?: return@collectLatest
                    launch { session.events.collect { event -> onEvent(computer, event) } }
                    session.status.collect { _online.value = it.state == LinkState.ONLINE }
                }
            }
    }

    override fun close() {
        following?.cancel()
        following = null
        _online.value = false
        computers.disconnect(computer.id)
    }

    override suspend fun beat(): Boolean? = computers.session(computer.id)?.alive()
}

/** Counts the app's activities on screen. */
private class Visibility : Application.ActivityLifecycleCallbacks {
    private val started = MutableStateFlow(0)
    val visible: Flow<Boolean> = started.map { it > 0 }.distinctUntilChanged()

    override fun onActivityStarted(activity: Activity) = started.update { it + 1 }

    override fun onActivityStopped(activity: Activity) = started.update { (it - 1).coerceAtLeast(0) }

    override fun onActivityCreated(
        activity: Activity,
        state: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        state: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * Tells the [ConnectionManager] what to keep: one link for every paired computer and every
 * message channel that is switched on, for as long as the user's switch allows or the app is on
 * screen. With nothing paired and no channel on, the list is empty and nothing runs.
 */
@Singleton
class LinkCenter
    @Inject
    constructor(
        private val manager: ConnectionManager,
        private val computers: RemoteComputers,
        private val settings: LinkSettings,
        private val watchers: AgentWatchers,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val visibility = Visibility()
        private val channels = MutableStateFlow<List<ManagedLink>>(emptyList())
        private val links = HashMap<String, ComputerLink>()

        @Volatile
        private var started = false

        /** Message channels that want to be kept (their own code switches them on and off). */
        fun setChannels(kept: List<ManagedLink>) {
            channels.value = kept
        }

        /** Called once, when the app's process starts. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun start(app: Application) {
            if (started) return
            started = true
            app.registerActivityLifecycleCallbacks(visibility)
            // Alerts follow the screen itself, not the lingering: unseen is unseen at once.
            scope.launch { visibility.visible.collect { watchers.appVisible = it } }
            scope.launch {
                computers.load()
                var first = true
                // Leaving the app does not cut a reply short: with the switch off, the links live a little longer.
                val onScreen =
                    visibility.visible.transformLatest { seen ->
                        if (!seen && !first) delay(LINGER_MS)
                        first = false
                        emit(seen)
                    }
                val bound = computers.computers
                val wanted =
                    combine(bound, channels, settings.background, onScreen) { paired, extra, unseen, seen ->
                        Wanted(paired.map(::linkOf) + extra, unseen, seen)
                    }
                wanted.collect(manager::apply)
            }
        }

        @Synchronized
        private fun linkOf(computer: PairedComputer): ManagedLink =
            links.getOrPut(computer.id) { ComputerLink(computer, computers, scope, watchers::took) }

        private companion object {
            const val LINGER_MS = 120_000L
        }
    }
