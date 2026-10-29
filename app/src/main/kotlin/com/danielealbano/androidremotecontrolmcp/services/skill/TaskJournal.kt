package com.danielealbano.androidremotecontrolmcp.services.skill

import javax.inject.Inject
import javax.inject.Singleton

/** Which of a task's recorded steps become its skill, and how the skill ends. */
data class ArchiveCut(
    /** Numbers of the recorded steps that were detours. */
    val drop: Set<Int> = emptySet(),
    /** The request was answered by reading: a replay ends in a reading too. */
    val thenRead: Boolean = false,
    /** Only the first so many steps are the way there; what came after depended on that day's screen. */
    val keep: Int? = null,
)

/** How to get back to an earlier point of a task: the way there from the app's launch, and what was done since. */
data class WayBack(
    val route: List<SkillStep>,
    val since: List<SkillStep>,
)

/** What a finished task turned into. */
data class ArchiveOutcome(
    val archived: Skill?,
    val message: String,
)

/**
 * Turns the steps of a task that went well into a skill. Pure: [TaskJournal] feeds it.
 *
 * The skill starts with a launch (added from the first control's app when the task began inside
 * an app), repeated typing into one field keeps only the final text, [params] turn the words that
 * change between runs into `{{name}}`, and taps on those words get checkpoints.
 */
object TaskArchive {
    const val MIN_STEPS = 2

    fun build(
        name: String,
        steps: List<SkillStep>,
        blockers: Collection<String>,
        params: Map<String, String>,
        thenRead: Boolean = false,
    ): Result<Skill> {
        val tidy = route(steps)
        val problem =
            when {
                blockers.isNotEmpty() -> "这次用了重放做不到的操作：${blockers.joinToString("；")}"
                name.isBlank() -> "缺少任务名称"
                tidy.count { it !is SkillStep.Launch } < MIN_STEPS -> "步骤太少，不值得存成技能"
                else -> null
            }
        if (problem != null) return Result.failure(IllegalArgumentException(problem))
        val title = name.trim()
        var skill =
            Skill(
                name = title,
                description = "自动归档：$title",
                steps = tidy,
                source = SkillSource.AGENT,
                thenRead = thenRead,
            )
        params.entries
            .filter { (p, v) -> p.isNotBlank() && v.isNotBlank() && mentions(skill, v) }
            .sortedByDescending { it.value.length }
            .forEach { (p, v) -> skill = SkillParams.parameterize(skill, p.trim(), v) }
        skill = SkillParams.withCheckpoints(skill)
        val invalid = SkillValidator.problems(skill)
        return if (invalid.isEmpty()) {
            Result.success(skill)
        } else {
            Result.failure(IllegalArgumentException(invalid.joinToString("；")))
        }
    }

    /** [steps] as a replay can follow them: from the app's launch, without reopening it, popups optional. */
    fun route(steps: List<SkillStep>): List<SkillStep> =
        dismissalsOptional(withoutReopening(withLaunch(mergeTyping(steps))))

    private fun mentions(
        skill: Skill,
        literal: String,
    ): Boolean {
        if (skill.name.contains(literal)) return true
        return skill.steps.any { step -> SkillText.literals(step).any { it.contains(literal) } }
    }

    /** Several edits of the same field in a row: only the final text matters on replay. */
    private fun mergeTyping(steps: List<SkillStep>): List<SkillStep> {
        val out = mutableListOf<SkillStep>()
        steps.forEach { step ->
            val last = out.lastOrNull()
            if (step is SkillStep.Type && last is SkillStep.Type && last.target == step.target) {
                out[out.lastIndex] = step
            } else {
                out += step
            }
        }
        return out
    }

    /**
     * A tap that only waves a popup away ("暂不升级", "跳过") was needed this time because the popup
     * happened to be there. Replayed as a must, it would fail every time the popup is not.
     */
    private fun dismissalsOptional(steps: List<SkillStep>): List<SkillStep> =
        steps.map { step ->
            val words = (step as? SkillStep.Tap)?.takeIf { !it.ocr }?.target?.text.orEmpty().lowercase()
            val dismisses = words.isNotEmpty() && DISMISSALS.any { words.contains(it) }
            if (step is SkillStep.Tap && dismisses) step.copy(optional = true, scroll = false) else step
        }

    /** Words of buttons that only put off or close what popped up. Not 取消 or 关闭: those can be the point of a step. */
    private val DISMISSALS =
        listOf(
            "暂不", "以后再说", "下次再说", "稍后", "跳过", "知道了", "不再提示", "残忍拒绝",
            "not now", "later", "skip", "no thanks", "got it",
        )

    /**
     * Opening the app again part-way (it froze, or the model lost its place) is not a step of the
     * way there: a replay that did it would throw away everything it had done so far.
     */
    private fun withoutReopening(steps: List<SkillStep>): List<SkillStep> {
        val first = steps.firstOrNull() as? SkillStep.Launch ?: return steps
        val later = steps.drop(1).filterNot { it is SkillStep.Launch && it.packageName == first.packageName }
        return listOf<SkillStep>(first) + later
    }

    /** A task started inside an app replays from that app's first screen. */
    private fun withLaunch(steps: List<SkillStep>): List<SkillStep> {
        if (steps.firstOrNull() is SkillStep.Launch) return steps
        val app =
            steps.firstNotNullOfOrNull {
                when (it) {
                    is SkillStep.Tap -> it.target.packageName
                    is SkillStep.Type -> it.target?.packageName
                    is SkillStep.Wait -> it.target.packageName
                    else -> null
                }
            }
        return if (app != null) listOf(SkillStep.Launch(app)) + steps else steps
    }
}

/**
 * The steps of the task in progress, as ARC saw the model do them through its tools. A task that
 * the model reports as done is archived as a skill (replacing an older one of the same name, which
 * is how a skill that broke gets repaired); next time the same request replays it without a model.
 */
@Singleton
class TaskJournal
    @Inject
    constructor(
        private val repository: SkillRepository,
    ) {
        private val lock = Any()
        private var goal: String? = null
        private val steps = mutableListOf<SkillStep>()
        private val blockers = linkedSetOf<String>()

        /**
         * Starts a task. The same goal as the task in progress continues it: after a skill for the
         * request broke half way, its good steps stay as the start of the repaired skill.
         */
        fun begin(goal: String) {
            synchronized(lock) {
                val wanted = goal.trim().takeIf { it.isNotEmpty() }
                val current = this.goal
                if (wanted != null && current != null && SkillMatcher.key(wanted) == SkillMatcher.key(current)) return
                this.goal = wanted
                steps.clear()
                blockers.clear()
            }
        }

        /** A replay started from the user's words: they become the goal when no task is in progress. */
        fun adopt(goal: String) {
            synchronized(lock) {
                if (this.goal == null) this.goal = goal.trim().takeIf { it.isNotEmpty() }
            }
        }

        /**
         * Adds a step. [fromTheStart] says the app was just opened at its first screen: whatever was
         * recorded before no longer leads anywhere, so the way starts again with this step.
         */
        fun record(
            step: SkillStep,
            fromTheStart: Boolean = false,
        ) {
            synchronized(lock) {
                if (fromTheStart) {
                    steps.clear()
                    blockers.clear()
                }
                steps += step
            }
        }

        fun recordAll(done: List<SkillStep>) {
            synchronized(lock) { steps += done }
        }

        /** Something was done that a replay cannot repeat; the task will not be archived. */
        fun cannotReplay(reason: String) {
            synchronized(lock) { blockers += reason }
        }

        val isEmpty: Boolean get() = synchronized(lock) { steps.isEmpty() && blockers.isEmpty() }

        /** The way to where the task stood after its first [count] recorded steps, and what was recorded since. */
        fun wayBack(count: Int): WayBack =
            synchronized(lock) {
                val kept = count.coerceAtLeast(0)
                WayBack(TaskArchive.route(steps.take(kept)), steps.drop(kept))
            }

        /** The phone was taken back to where it stood after [count] steps: what was recorded since is off the way. */
        fun rewind(count: Int) {
            synchronized(lock) {
                while (steps.size > count.coerceAtLeast(0)) steps.removeAt(steps.lastIndex)
            }
        }

        fun summary(): String =
            synchronized(lock) {
                val head = "goal: ${goal ?: "(none)"}; steps: ${steps.size}"
                val status = if (blockers.isEmpty()) head else "$head; cannot archive: ${blockers.joinToString("; ")}"
                val listed = steps.mapIndexed { index, step -> "${index + 1}. ${SkillText.describe(step)}" }
                (listOf(status) + listed).joinToString("\n")
            }

        /**
         * Ends the task; a successful one becomes a skill named [name] (default: the goal), made of
         * the recorded steps that [cut] keeps.
         */
        suspend fun end(
            success: Boolean,
            name: String?,
            params: Map<String, String>,
            cut: ArchiveCut = ArchiveCut(),
        ): ArchiveOutcome {
            val (taskGoal, done, blocked) =
                synchronized(lock) {
                    val snapshot = Triple(goal, steps.toList(), blockers.toList())
                    goal = null
                    steps.clear()
                    blockers.clear()
                    snapshot
                }
            if (!success) return ArchiveOutcome(null, "任务没有成功，这次的步骤没有存")
            val skillName = name?.trim()?.takeIf { it.isNotEmpty() } ?: taskGoal
            val kept = done.take(cut.keep ?: done.size).filterIndexed { index, _ -> (index + 1) !in cut.drop }
            return TaskArchive
                .build(skillName.orEmpty(), kept, blocked, params, cut.thenRead)
                .fold(
                    onSuccess = { save(it) },
                    onFailure = { ArchiveOutcome(null, "没有存成技能：${it.message}") },
                )
        }

        private suspend fun save(built: Skill): ArchiveOutcome =
            repository
                .save(built, replace = true)
                .fold(
                    onSuccess = { ArchiveOutcome(it, "已存成技能「${it.name}」（${it.steps.size} 步），下次同样的请求直接重放") },
                    onFailure = { ArchiveOutcome(null, "没有存成技能：${it.message}") },
                )
    }
