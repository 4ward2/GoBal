package com.danielealbano.androidremotecontrolmcp.services.skill

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place skills are run from (MCP tools, the on-device agent through them, the settings
 * page). Only one replay drives the screen at a time; a second request is refused, not queued,
 * because two replays would tap into each other's screens.
 */
@Singleton
class SkillCoordinator
    internal constructor(
        private val repository: SkillRepository,
        private val runnerFactory: () -> SkillRunner,
        private val saveCollected: (SkillRunResult) -> String? = { null },
    ) {
        @Inject
        constructor(
            repository: SkillRepository,
            device: AndroidSkillDevice,
            files: CollectionFiles,
        ) : this(repository, { SkillRunner(device) }, files::save)

        private val mutex = Mutex()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val _running = MutableStateFlow<String?>(null)

        /** Name of the skill being replayed, or null. */
        val running: StateFlow<String?> = _running.asStateFlow()

        private val _lastResult = MutableStateFlow<SkillRunResult?>(null)
        val lastResult: StateFlow<SkillRunResult?> = _lastResult.asStateFlow()

        suspend fun run(
            name: String,
            params: Map<String, String>,
        ): SkillRunResult {
            repository.load()
            val skill =
                repository.find(name)
                    ?: return refused(name, "没有叫「$name」的技能")
            return runSkill(skill, params)
        }

        /** Runs the skill whose name the sentence says, or returns null when none matches. */
        suspend fun runSpoken(utterance: String): SkillRunResult? {
            repository.load()
            val match = SkillMatcher.match(utterance, repository.skills.value) ?: return null
            return runSkill(match.skill, match.params)
        }

        /** Replays [steps] that belong to no saved skill: the recorded way back to a place in a running task. */
        suspend fun runSteps(
            name: String,
            steps: List<SkillStep>,
        ): SkillRunResult = runSkill(Skill(name = name, steps = steps), emptyMap(), saved = false)

        /** Fire-and-forget run for the settings page; the outcome lands in [lastResult]. */
        fun start(
            name: String,
            params: Map<String, String> = emptyMap(),
        ) {
            scope.launch { run(name, params) }
        }

        private suspend fun runSkill(
            skill: Skill,
            params: Map<String, String>,
            saved: Boolean = true,
        ): SkillRunResult {
            if (!mutex.tryLock()) {
                return refused(skill.name, "「${_running.value ?: "另一个技能"}」还在运行，等它结束再试")
            }
            return try {
                _running.value = skill.name
                Log.i(TAG, "run skill '${skill.name}' (${skill.steps.size} steps)")
                val ran = withContext(Dispatchers.Default) { runnerFactory().run(skill, params) }
                val result = ran.copy(savedTo = withContext(Dispatchers.IO) { saveCollected(ran) })
                if (saved) repository.recordRun(skill.name, result.status != SkillRunStatus.FAILED)
                Log.i(TAG, "skill '${skill.name}' ${result.status} at ${result.stoppedAt ?: "-"}")
                _lastResult.value = result
                result
            } finally {
                _running.value = null
                mutex.unlock()
            }
        }

        /** A run that did not start: it is the latest outcome too, so nothing of an older run is mistaken for it. */
        private fun refused(
            name: String,
            message: String,
        ): SkillRunResult {
            val result = SkillRunResult(SkillRunStatus.FAILED, name, 0, 0, null, message, emptyList())
            _lastResult.value = result
            return result
        }

        private companion object {
            const val TAG = "MCP:Skills"
        }
    }
