package com.danielealbano.androidremotecontrolmcp.services.group

import com.danielealbano.androidremotecontrolmcp.services.chat.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One model a member may answer with: which provider, and which of its models. */
@Serializable
data class SubModel(
    val providerId: String,
    val model: String,
)

/**
 * A member of a group chat: the assistant, a character, or an agent on a paired computer.
 * A member on the phone may answer with one of several models; each is then a worker of its own
 * that the admins can address. An agent on a computer brings its own model and is one worker.
 */
@Serializable
data class GroupMember(
    /** "assistant", "char:<id>" or "remote:<computer>/<agent>". */
    val id: String,
    val name: String,
    /** True for an agent on a paired computer. */
    val remote: Boolean = false,
    /** The models it may answer with; empty for an agent on a computer. */
    val models: List<SubModel> = emptyList(),
    /** Who it is, for its system prompt; "" for an agent on a computer (it has its own). */
    val persona: String = "",
    /** An admin hands out the tasks and accepts or sends back the results. */
    val admin: Boolean = false,
)

@Serializable
data class Group(
    val id: String,
    val name: String,
    val members: List<GroupMember>,
    val createdAt: Long = 0,
) {
    /** Everyone a task can be given to, by the handle the admins write after the "@". */
    val workers: List<GroupWorker>
        get() =
            members.flatMap { member ->
                val several = member.models.size > 1
                val models = member.models.ifEmpty { listOf(null) }
                models.map { model ->
                    val handle = if (several && model != null) member.name + "·" + model.model else member.name
                    GroupWorker(handle, member, model)
                }
            }

    val admins: List<GroupWorker> get() = workers.filter { it.member.admin }.distinctBy { it.member.id }
}

/** One addressable worker: a member answering with one model. */
data class GroupWorker(
    val handle: String,
    val member: GroupMember,
    /** Null for an agent on a computer. */
    val model: SubModel?,
)

/** One line of a group's conversation. */
@Serializable
data class GroupMessage(
    val id: String,
    /** "user" or a worker's handle. */
    val from: String,
    val text: String,
    val at: Long,
    /** True when an admin wrote it. */
    val admin: Boolean = false,
    /** Whom the task was for, when this is the result of one. */
    val taskFor: String = "",
)

/** The groups and their conversations on disk: one list, and one file of messages per group. */
class GroupStore(
    private val dir: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val index = File(dir, "groups.json")

    @Synchronized
    fun groups(): List<Group> = read<List<Group>>(index).orEmpty()

    @Synchronized
    fun save(group: Group) {
        AtomicFiles.write(index, json.encodeToString(groups().filterNot { it.id == group.id } + group))
    }

    @Synchronized
    fun delete(id: String) {
        AtomicFiles.write(index, json.encodeToString(groups().filterNot { it.id == id }))
        File(dir, "$id.json").delete()
    }

    @Synchronized
    fun messages(id: String): List<GroupMessage> = read<List<GroupMessage>>(File(dir, "$id.json")).orEmpty()

    @Synchronized
    fun append(
        id: String,
        message: GroupMessage,
    ) {
        AtomicFiles.write(File(dir, "$id.json"), json.encodeToString(messages(id) + message))
    }

    private inline fun <reified T> read(file: File): T? {
        val text = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        return text?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
    }
}
