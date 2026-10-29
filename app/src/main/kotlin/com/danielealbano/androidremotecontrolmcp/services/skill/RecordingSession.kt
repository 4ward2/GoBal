package com.danielealbano.androidremotecontrolmcp.services.skill

import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class RecordMode {
    NONE,

    /** The user says what to do; the phone agent does it and the run is archived as a skill. */
    AI,

    /** The user does it by hand; the floating ball controls the recording. */
    MANUAL,
}

/**
 * Which recording is going on, for the chat (its input box glows while one is) and for the voice
 * wake (an AI recording sends the spoken request straight to the phone agent). A manual recording
 * that finished leaves the saved skill's name in [review] for the chat to open and discuss.
 */
@Singleton
class RecordingSession
    @Inject
    constructor() {
        private val _mode = MutableStateFlow(RecordMode.NONE)
        val mode: StateFlow<RecordMode> = _mode.asStateFlow()

        private val _review = MutableStateFlow<String?>(null)
        val review: StateFlow<String?> = _review.asStateFlow()

        fun begin(mode: RecordMode) {
            _mode.value = mode
        }

        fun end() {
            _mode.value = RecordMode.NONE
        }

        fun offerReview(skillName: String) {
            _review.value = skillName
        }

        /** The recording waiting for the chat, taken once. */
        fun takeReview(): String? {
            val name = _review.value
            _review.value = null
            return name
        }
    }

/**
 * The manual recording as the floating ball drives it: start, pause / resume, steps picked on a
 * screenshot, and stop — which saves the recording as a skill right away (nothing is lost if the
 * chat never opens) and brings the user back to the chat, where the agent goes through it.
 */
@Singleton
class ManualRecording
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val recorder: SkillRecorder,
        private val repository: SkillRepository,
        private val session: RecordingSession,
    ) {
        fun start() {
            recorder.start { context.packageManager.getLaunchIntentForPackage(it) != null }
            session.begin(RecordMode.MANUAL)
        }

        val isPaused: Boolean get() = (recorder.state.value as? RecorderState.Recording)?.paused == true

        fun togglePause() {
            val paused = (recorder.state.value as? RecorderState.Recording)?.paused ?: return
            recorder.setPaused(!paused)
        }

        fun setPaused(paused: Boolean) = recorder.setPaused(paused)

        fun append(steps: List<SkillStep>) = recorder.append(steps)

        fun cancel() {
            recorder.cancel()
            session.end()
        }

        /** Saves what was recorded and opens the chat; false when nothing replayable was recorded. */
        suspend fun stop(): Boolean {
            val steps = recorder.takeSteps()
            session.end()
            val skill = steps.takeIf { it.isNotEmpty() }?.let { save(it) } ?: return false
            session.offerReview(skill.name)
            val open =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_TAB, TopLevelRoute.Chat.route)
            context.startActivity(open)
            return true
        }

        private suspend fun save(steps: List<SkillStep>): Skill? {
            repository.load()
            val skill = Skill(name = freeName(), steps = steps, source = SkillSource.RECORDED)
            return repository.save(skill, replace = false).getOrNull()
        }

        /** "录制的操作 09-29 10:30", with a counter when that minute is taken. */
        private fun freeName(): String {
            val base = "录制的操作 " + SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date())
            var name = base
            var n = 2
            while (repository.find(name) != null) name = "$base ($n)".also { n++ }
            return name
        }
    }
