package com.danielealbano.androidremotecontrolmcp.services.remote

/** An agent running on a paired computer, as its daemon describes it. */
data class RemoteAgent(
    val id: String,
    val title: String,
    /** "claude", "codex", … */
    val provider: String,
    val model: String,
    val cwd: String,
    /** "idle", "running", "error", … as the daemon says it. */
    val status: String,
    val archived: Boolean = false,
    /** What it is waiting to be allowed to do. */
    val pending: List<RemotePermission> = emptyList(),
    /** When it last did something, in epoch milliseconds; 0 when the daemon did not say. */
    val updatedAt: Long = 0,
    /** Why it stopped the last time it failed, as the daemon says it; "" when it did not fail. */
    val lastError: String = "",
    /** When the user last said something to it, in epoch milliseconds; 0 when never or unknown. */
    val lastUserMessageAt: Long = 0,
)

/** Something an agent asks leave to do. */
data class RemotePermission(
    val id: String,
    /** The tool or the kind of question ("Bash", "plan", …). */
    val name: String,
    val title: String,
    /** What exactly it wants to do, in a few lines the user can judge. */
    val detail: String,
    /** The answers the agent itself offers ("allow once", "always in this folder"…); empty means allow or deny. */
    val choices: List<RemoteChoice> = emptyList(),
)

/** One answer an agent offers to its own permission request. */
data class RemoteChoice(
    val id: String,
    val label: String,
    val allow: Boolean,
)

/** What the user tapped: allow or deny, which of the agent's own answers, and an optional word to the agent. */
data class PermissionReply(
    val allow: Boolean,
    val choiceId: String? = null,
    val message: String? = null,
)

/** One entry of an agent's conversation. */
sealed interface RemoteItem {
    data class User(
        val text: String,
        val messageId: String,
    ) : RemoteItem

    /** A piece of the agent's answer. Pieces with the same [messageId] are one message, in order. */
    data class Assistant(
        val text: String,
        val messageId: String,
    ) : RemoteItem

    data class Reasoning(
        val text: String,
    ) : RemoteItem

    data class Tool(
        val callId: String,
        val name: String,
        /** What it ran or touched, one line. */
        val summary: String,
        /** "running", "completed", "failed", … */
        val status: String,
    ) : RemoteItem
}

/** What happens on a computer while the phone is connected to it, whichever program runs there. */
sealed interface RemoteEvent {
    data class AgentChanged(
        val agent: RemoteAgent,
    ) : RemoteEvent

    data class AgentRemoved(
        val agentId: String,
    ) : RemoteEvent

    data class TurnStarted(
        val agentId: String,
    ) : RemoteEvent

    /** The agent stopped answering: it finished, failed ([error]) or was cancelled. */
    data class TurnEnded(
        val agentId: String,
        val error: String,
        val cancelled: Boolean,
    ) : RemoteEvent

    data class Item(
        val agentId: String,
        val item: RemoteItem,
    ) : RemoteEvent

    data class PermissionAsked(
        val agentId: String,
        val permission: RemotePermission,
    ) : RemoteEvent

    data class PermissionSettled(
        val agentId: String,
        val permissionId: String,
    ) : RemoteEvent
}

/** What it takes to start an agent on a computer. */
data class NewAgent(
    val provider: String,
    val cwd: String,
    val model: String = "",
    val title: String = "",
    /** The agent's permission mode as its program names it ("plan", "default", …); "" for its default. */
    val mode: String = "",
)

/** A program on the computer that can run agents ("claude", "codex", …), as the computer reports it. */
data class RemoteProvider(
    val id: String,
    /** False when it is installed but switched off or not signed in. */
    val available: Boolean,
    val error: String = "",
)

/** A model a provider offers right now. */
data class RemoteModel(
    val id: String,
    val label: String,
    val description: String = "",
    val isDefault: Boolean = false,
    /** Its context window in tokens; 0 when the computer does not say. */
    val contextWindow: Int = 0,
)

/** A permission mode a provider's agents can run in. */
data class RemoteMode(
    val id: String,
    val label: String,
    val description: String = "",
)

/** What a new agent of one provider can be started with. */
data class ProviderChoices(
    val models: List<RemoteModel> = emptyList(),
    val modes: List<RemoteMode> = emptyList(),
)
