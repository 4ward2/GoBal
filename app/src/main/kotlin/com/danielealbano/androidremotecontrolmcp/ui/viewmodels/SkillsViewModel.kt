package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.skill.ManualRecording
import com.danielealbano.androidremotecontrolmcp.services.skill.RecorderState
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillCoordinator
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillParams
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRecorder
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRepository
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunResult
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings → 技能: the saved skills, recording a new one and editing a draft. */
@HiltViewModel
class SkillsViewModel
    @Inject
    constructor(
        private val repository: SkillRepository,
        private val recorder: SkillRecorder,
        private val manual: ManualRecording,
        private val coordinator: SkillCoordinator,
        private val accessibility: AccessibilityServiceProvider,
    ) : ViewModel() {
        val skills: StateFlow<List<Skill>> = repository.skills
        val recorderState: StateFlow<RecorderState> = recorder.state
        val draft: StateFlow<Skill?> = recorder.draft
        val running: StateFlow<String?> = coordinator.running
        val lastResult: StateFlow<SkillRunResult?> = coordinator.lastResult

        private val _message = MutableStateFlow<String?>(null)

        /** Result of the last save / validation, shown under the editor. */
        val message: StateFlow<String?> = _message.asStateFlow()

        /** Name of the stored skill the draft was opened from (null for a new recording). */
        private var editingName: String? = null

        init {
            viewModelScope.launch { repository.load() }
        }

        /** Starts recording; false when the accessibility service is off (nothing could be seen). */
        fun startRecording(): Boolean {
            if (!accessibility.isReady()) return false
            editingName = null
            manual.start()
            return true
        }

        fun edit(skill: Skill) {
            editingName = skill.name
            _message.value = null
            recorder.setDraft(skill)
        }

        fun discardDraft() {
            editingName = null
            _message.value = null
            recorder.setDraft(null)
        }

        fun makeParam(
            literal: String,
            param: String,
        ) {
            val current = draft.value ?: return
            runCatching { SkillParams.parameterize(current, param, literal) }
                .onSuccess {
                    _message.value = null
                    recorder.setDraft(it)
                }.onFailure { _message.value = it.message }
        }

        fun saveDraft() {
            val current = draft.value ?: return
            viewModelScope.launch {
                val skill = SkillParams.withCheckpoints(current.copy(name = current.name.trim()))
                repository
                    .save(skill, replace = editingName != null, previousName = editingName)
                    .onSuccess {
                        editingName = null
                        _message.value = null
                        recorder.setDraft(null)
                    }.onFailure { _message.value = it.message }
            }
        }

        fun delete(name: String) {
            viewModelScope.launch { repository.delete(name) }
        }

        fun run(name: String) = coordinator.start(name)

        /** Applies [change] to the draft being edited; no-op when there is none. */
        internal fun updateDraft(change: (Skill) -> Skill) {
            draft.value?.let { recorder.setDraft(change(it)) }
        }
    }

// Plain edits of the draft (name, parameters, steps), kept apart from the view model's lifecycle work.

fun SkillsViewModel.rename(name: String) = updateDraft { it.copy(name = name) }

fun SkillsViewModel.setDescription(text: String) = updateDraft { it.copy(description = text) }

fun SkillsViewModel.setParamDefault(
    param: String,
    value: String,
) = updateDraft { d ->
    d.copy(params = d.params.map { if (it.name == param) it.copy(defaultValue = value) else it })
}

fun SkillsViewModel.removeStep(index: Int) =
    updateDraft { d ->
        d.copy(steps = d.steps.filterIndexed { i, _ -> i != index })
    }

fun SkillsViewModel.addVerifyAfter(
    index: Int,
    text: String,
) {
    if (text.isBlank()) return
    updateDraft { d ->
        d.copy(steps = d.steps.toMutableList().apply { add(index + 1, SkillStep.Verify(text.trim())) })
    }
}

fun SkillsViewModel.addHandOverAtEnd() =
    updateDraft { d ->
        d.copy(steps = d.steps + SkillStep.HandOver("到这里交给你确认"))
    }
