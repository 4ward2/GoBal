package com.danielealbano.androidremotecontrolmcp.services.call

/** What the call is doing right now, as shown on the call screen. */
enum class CallPhase {
    LISTENING,
    THINKING,
    SPEAKING,
}

/** What the controller wants the session to do after an event. */
sealed interface TurnAction {
    /** Send [text] to the model as the user's next turn. */
    data class Send(
        val text: String,
    ) : TurnAction

    data object None : TurnAction
}

/**
 * Turn-taking without barge-in: the phone finishes what it is saying no matter what, but keeps
 * listening the whole time. Utterances that arrive while it thinks or speaks are queued and sent
 * together once it has finished; utterances that arrive while it listens are sent after a short
 * pause so a person who stops to breathe is not cut in half. Pure, so it is unit-tested.
 */
class TurnController(
    private val pauseMs: Long = DEFAULT_PAUSE_MS,
) {
    var phase: CallPhase = CallPhase.LISTENING
        private set

    private val pending = StringBuilder()
    private var lastFinalAt = 0L

    /** The recogniser produced a final utterance (echoes already filtered out). */
    fun onUserFinal(
        text: String,
        nowMs: Long,
    ): TurnAction {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TurnAction.None
        if (pending.isNotEmpty()) pending.append(' ')
        pending.append(trimmed)
        lastFinalAt = nowMs
        return TurnAction.None
    }

    /**
     * Called on a timer while listening: once the person has been quiet for [pauseMs] after their
     * last utterance, whatever was queued goes to the model.
     */
    fun onTick(nowMs: Long): TurnAction {
        if (phase != CallPhase.LISTENING || pending.isEmpty()) return TurnAction.None
        if (nowMs - lastFinalAt < pauseMs) return TurnAction.None
        return flush()
    }

    /** The model started producing a reply. */
    fun onAssistantStarted() {
        phase = CallPhase.THINKING
    }

    /** Audio of the reply started playing. */
    fun onAssistantSpeaking() {
        phase = CallPhase.SPEAKING
    }

    /**
     * The reply has been fully played (or failed). Anything the person said meanwhile is sent
     * right away: they have waited long enough.
     */
    fun onAssistantFinished(): TurnAction {
        phase = CallPhase.LISTENING
        return if (pending.isEmpty()) TurnAction.None else flush()
    }

    private fun flush(): TurnAction {
        val text = pending.toString()
        pending.setLength(0)
        phase = CallPhase.THINKING
        return TurnAction.Send(text)
    }

    companion object {
        const val DEFAULT_PAUSE_MS = 700L
    }
}
