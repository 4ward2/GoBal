package com.danielealbano.androidremotecontrolmcp.services.skill

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the accessibility service saw the user do, already reduced to plain data. */
sealed interface RecordedEvent {
    val packageName: String

    /** A window of [packageName] came to the front. [ignored]: launcher, system UI, keyboard, this app. */
    data class WindowShown(
        override val packageName: String,
        val ignored: Boolean,
    ) : RecordedEvent

    /** The user tapped a control; [selector] is null when it had nothing to recognise it by. */
    data class Clicked(
        override val packageName: String,
        val selector: NodeSelector?,
    ) : RecordedEvent

    /** The text of an input field changed to [text]. */
    data class TextChanged(
        override val packageName: String,
        val field: NodeSelector?,
        val text: String,
    ) : RecordedEvent
}

/**
 * Turns a stream of [RecordedEvent]s into skill steps. Pure; the caller feeds it in event order.
 * [launchable]: whether a package can be opened on its own (settings search, share sheets cannot).
 */
class RecordingReducer(
    private val launchable: (String) -> Boolean = { true },
) {
    private val steps = mutableListOf<SkillStep>()
    private var app: String? = null
    private var cameFromOutside = true

    /** Clicks that had no text, description or resource id to replay by. */
    var skipped: Int = 0
        private set

    val result: List<SkillStep> get() = steps.toList()

    /** A step made outside the event stream (a text or picture picked on a screenshot). */
    fun append(step: SkillStep) {
        steps += step
    }

    fun apply(event: RecordedEvent) {
        when (event) {
            is RecordedEvent.WindowShown -> onWindow(event)
            is RecordedEvent.Clicked -> onClick(event)
            is RecordedEvent.TextChanged -> onText(event)
        }
    }

    private fun onWindow(event: RecordedEvent.WindowShown) {
        if (event.ignored) {
            cameFromOutside = true
            return
        }
        val entered = event.packageName != app && cameFromOutside
        app = event.packageName
        // A part of another app that cannot be opened by itself (settings search, a share sheet) gives
        // no step; the next app reached from outside that can be opened does.
        if (entered && !launchable(event.packageName)) return
        // Reached from the launcher / recents (not by a tap inside the flow): replay must open it.
        if (entered) steps += SkillStep.Launch(event.packageName)
        cameFromOutside = false
    }

    private fun onClick(event: RecordedEvent.Clicked) {
        if (event.packageName != app) onWindow(RecordedEvent.WindowShown(event.packageName, ignored = false))
        // Whatever this tap opens is reached inside the flow.
        cameFromOutside = false
        val selector = event.selector?.takeUnless { it.isEmpty }
        if (selector == null) {
            skipped++
            return
        }
        steps += SkillStep.Tap(selector.copy(packageName = event.packageName))
    }

    private fun onText(event: RecordedEvent.TextChanged) {
        if (event.packageName != app) onWindow(RecordedEvent.WindowShown(event.packageName, ignored = false))
        val field = event.field?.takeUnless { it.isEmpty }?.copy(packageName = event.packageName)
        val last = steps.lastOrNull()
        // Typing arrives one change at a time; only the final text of a field matters.
        if (last is SkillStep.Type && last.target == field) {
            steps[steps.lastIndex] = last.copy(text = event.text)
        } else {
            steps += SkillStep.Type(field, event.text)
        }
    }
}

sealed interface RecorderState {
    data object Idle : RecorderState

    data class Recording(
        val steps: Int,
        val skipped: Int,
        /** Paused from the floating ball: what the user does meanwhile is not recorded. */
        val paused: Boolean = false,
    ) : RecorderState
}

/**
 * The user demonstrates a flow once; the accessibility service feeds what it sees through
 * [onEvent], and [finish] leaves an unnamed [draft] for the editor. Process-wide, like the
 * accessibility service it is fed by.
 */
@Singleton
class SkillRecorder
    @Inject
    constructor() {
        private val lock = Any()
        private var reducer: RecordingReducer? = null

        private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
        val state: StateFlow<RecorderState> = _state.asStateFlow()

        private val _draft = MutableStateFlow<Skill?>(null)

        /** A finished recording waiting to be named and saved (or an existing skill being edited). */
        val draft: StateFlow<Skill?> = _draft.asStateFlow()

        val isRecording: Boolean get() = _state.value is RecorderState.Recording

        /** Recording and not paused: user actions become steps. */
        val isListening: Boolean get() = (_state.value as? RecorderState.Recording)?.paused == false

        fun start(launchable: (String) -> Boolean = { true }) {
            synchronized(lock) {
                reducer = RecordingReducer(launchable)
                _state.value = RecorderState.Recording(0, 0)
            }
        }

        fun onEvent(event: RecordedEvent) {
            synchronized(lock) {
                val r = reducer ?: return
                if (!isListening) return
                r.apply(event)
                publish(r, paused = false)
            }
        }

        /** Adds steps the user made on a screenshot (OCR taps, checkpoints), in order. */
        fun append(steps: List<SkillStep>) {
            synchronized(lock) {
                val r = reducer ?: return
                steps.forEach(r::append)
                publish(r, paused = (_state.value as? RecorderState.Recording)?.paused == true)
            }
        }

        /** Stops recording and hands back the steps, without opening the settings editor. */
        fun takeSteps(): List<SkillStep> =
            synchronized(lock) {
                val steps = reducer?.result.orEmpty()
                reducer = null
                _state.value = RecorderState.Idle
                steps
            }

        /** Paused from the floating ball: what the user does meanwhile is not recorded. */
        fun setPaused(paused: Boolean) {
            synchronized(lock) {
                reducer?.let { publish(it, paused) }
            }
        }

        private fun publish(
            r: RecordingReducer,
            paused: Boolean,
        ) {
            _state.value = RecorderState.Recording(r.result.size, r.skipped, paused)
        }

        /** Stops recording; returns false (and keeps no draft) when nothing replayable was recorded. */
        fun finish(): Boolean =
            synchronized(lock) {
                val steps = reducer?.result.orEmpty()
                reducer = null
                _state.value = RecorderState.Idle
                if (steps.isNotEmpty()) _draft.value = Skill(name = "", steps = steps, source = SkillSource.RECORDED)
                steps.isNotEmpty()
            }

        fun cancel() {
            synchronized(lock) {
                reducer = null
                _state.value = RecorderState.Idle
            }
        }

        /** Opens [skill] in the editor (or replaces the draft being edited); null closes it. */
        fun setDraft(skill: Skill?) {
            _draft.value = skill
        }
    }
