package com.danielealbano.androidremotecontrolmcp.services.group

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A task an admin gave to a worker. */
data class Assignment(
    val worker: GroupWorker,
    val task: String,
)

/** What the admins and the members are told, and how an admin's reply is read. Pure. */
object GroupProtocol {
    /**
     * The tasks in an admin's reply: every line that begins with "@handle" followed by a colon is
     * one, for the worker with that handle. The longest handle wins, so "@小夏·m-pro" is not read
     * as a task for "小夏". A reply without such a line gives out nothing: it is the conclusion.
     */
    fun assignments(
        reply: String,
        workers: List<GroupWorker>,
    ): List<Assignment> {
        val byLength = workers.sortedByDescending { it.handle.length }
        return reply.lines().mapNotNull { line ->
            val text = line.trim().removePrefix("-").trim()
            val worker = byLength.firstOrNull { text.startsWith("@" + it.handle) }
            val task = worker?.let { text.removePrefix("@" + it.handle).trimStart() }
            val said = task?.takeIf { it.startsWith(":") || it.startsWith("：") }?.drop(1)?.trim()
            if (worker != null && !said.isNullOrEmpty()) Assignment(worker, said) else null
        }
    }

    /** What an admin is told before it speaks. */
    fun adminBrief(
        group: Group,
        admin: GroupWorker,
    ): String {
        val roster = group.workers.map { "- @${it.handle}" + if (it.member.admin) "（管理员）" else "" }
        val lines =
            listOf("你是群聊「${group.name}」的管理员「${admin.handle}」。${admin.member.persona}") +
                "你的工作：把用户的要求拆成任务派给群成员，收到结果后验收，不合格的打回重做，全部合格后给用户一个总结。" +
                "群成员：" + roster +
                "派任务或打回时，每个任务单独一行，格式严格为：" +
                "@成员名: 要它做的事（写清楚要交什么）" +
                "一行只派给一个成员；成员互相看不到别人的任务，需要的背景要写在任务里。" +
                "如果不需要再派任务，就不要写任何以 @ 开头的行，直接写给用户的结论。" +
                "简单的问题可以自己直接回答，不必派任务。"
        return lines.joinToString("\n")
    }

    /** What a member is told with a task. */
    fun memberBrief(
        group: Group,
        worker: GroupWorker,
    ): String {
        val who = "你是群聊「${group.name}」的成员「${worker.handle}」。${worker.member.persona}"
        return who + "\n管理员会给你派任务。只交付任务要求的内容，简洁、可直接验收。"
    }

    /** The conversation so far, as one text for whoever speaks next. */
    fun transcript(messages: List<GroupMessage>): String =
        messages.joinToString("\n\n") { message ->
            val who = if (message.from == USER) "用户" else message.from
            val about = if (message.taskFor.isEmpty()) "" else "（交付任务）"
            "【$who$about】\n${message.text}"
        }

    const val USER = "user"
}

/** Lets a worker speak: [system] is who it is and what it is to do, [text] what it is answering. */
fun interface GroupSpeaker {
    suspend fun speak(
        worker: GroupWorker,
        system: String,
        text: String,
    ): Result<String>
}

/**
 * Runs a group's work on one request: the admins hand out tasks, the members deliver, the
 * admins review and send back what is not good enough, until an admin's round gives out nothing
 * more (its words are then the conclusion) or the rounds run out. Every line said is handed to
 * [post] as it is said.
 */
class GroupEngine(
    private val speaker: GroupSpeaker,
    private val clock: () -> Long,
    private val post: suspend (GroupMessage) -> Unit,
) {
    private var counter = 0

    suspend fun run(
        group: Group,
        history: List<GroupMessage>,
    ) {
        val said = history.toMutableList()
        val admins = group.admins.ifEmpty { group.workers.take(1) }
        var rounds = 0
        var open = admins.isNotEmpty()
        while (open && rounds < MAX_ROUNDS) {
            rounds++
            val tasks = admins.flatMap { admin -> adminTurn(group, admin, said) }
            deliver(group, tasks, said)
            open = tasks.isNotEmpty()
        }
        if (open) note(said, OUT_OF_ROUNDS)
    }

    /** One admin speaks; returns the tasks it gave out. */
    private suspend fun adminTurn(
        group: Group,
        admin: GroupWorker,
        said: MutableList<GroupMessage>,
    ): List<Assignment> {
        val reply = speaker.speak(admin, GroupProtocol.adminBrief(group, admin), GroupProtocol.transcript(said))
        val text = reply.getOrElse { "（${admin.handle} 没有回应：${it.message.orEmpty()}）" }
        add(said, GroupMessage(nextId(), admin.handle, text, clock(), admin = true))
        // An admin does not hand a task to itself: it would be talking to its own echo.
        val given = if (reply.isSuccess) GroupProtocol.assignments(text, group.workers) else emptyList()
        return given.filter { it.worker != admin }
    }

    /** The members work side by side; their results are posted in the order the tasks were given. */
    private suspend fun deliver(
        group: Group,
        tasks: List<Assignment>,
        said: MutableList<GroupMessage>,
    ) {
        val results =
            coroutineScope {
                val asked =
                    tasks.map { task ->
                        async { speaker.speak(task.worker, GroupProtocol.memberBrief(group, task.worker), task.task) }
                    }
                asked.awaitAll()
            }
        tasks.zip(results).forEach { (task, result) ->
            val text = result.getOrElse { "（没有完成：${it.message.orEmpty()}）" }
            add(said, GroupMessage(nextId(), task.worker.handle, text, clock(), taskFor = task.worker.handle))
        }
    }

    private suspend fun note(
        said: MutableList<GroupMessage>,
        text: String,
    ) = add(said, GroupMessage(nextId(), NOTE, text, clock()))

    private suspend fun add(
        said: MutableList<GroupMessage>,
        message: GroupMessage,
    ) {
        said += message
        post(message)
    }

    private fun nextId(): String = "g${clock()}-${counter++}"

    companion object {
        const val MAX_ROUNDS = 4
        const val NOTE = "群聊"
        const val OUT_OF_ROUNDS = "来回了 $MAX_ROUNDS 轮还没验收完，先停在这里。可以再说一句让它们继续。"
    }
}
