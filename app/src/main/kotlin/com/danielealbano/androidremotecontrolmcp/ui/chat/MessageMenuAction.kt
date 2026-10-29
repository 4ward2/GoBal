package com.danielealbano.androidremotecontrolmcp.ui.chat

/** The long-press menu's entries. */
enum class MessageMenuAction {
    COPY,
    REPLY,
    SELECT,
    READ,
    STOP_READING,
    REMEMBER,
    EDIT,
    REGENERATE,
    FORWARD,
    OPEN,
    EXCLUDE,
    INCLUDE,
    MULTI_SELECT,
    DELETE,
}

/** What the long-press menu needs to know about the message beyond its content. */
class MessageMarks(
    /** Left out of what the model is sent. */
    val excluded: Boolean,
    /** Being read aloud right now. */
    val reading: Boolean,
)

/**
 * Which entries a message offers: pictures cannot be copied, only documents open elsewhere, only
 * the user's own words can be edited and only an answer to a question can be asked for again.
 * [excluded]: the message is currently left out of what the model is sent. [reading]: it is
 * being read aloud right now.
 */
fun menuFor(
    item: ChatItem,
    excluded: Boolean = false,
    reading: Boolean = false,
    simple: Boolean = false,
): List<MessageMenuAction> {
    val attachment = attachmentOf(item)
    val text = textOf(item)
    if (simple) return simpleMenu(text, reading)
    return buildList {
        if (text.isNotBlank()) add(MessageMenuAction.COPY)
        add(MessageMenuAction.REPLY)
        if (text.isNotBlank()) add(MessageMenuAction.SELECT)
        if (text.isNotBlank()) add(if (reading) MessageMenuAction.STOP_READING else MessageMenuAction.READ)
        // What is kept out of the model's context is not made a memory either.
        if (text.isNotBlank() && !excluded) add(MessageMenuAction.REMEMBER)
        if (item is ChatItem.UserMessage) add(MessageMenuAction.EDIT)
        if (item is ChatItem.AssistantMessage && item.regenerable) add(MessageMenuAction.REGENERATE)
        add(MessageMenuAction.FORWARD)
        if (attachment != null && !attachment.isImage) add(MessageMenuAction.OPEN)
        add(if (excluded) MessageMenuAction.INCLUDE else MessageMenuAction.EXCLUDE)
        add(MessageMenuAction.MULTI_SELECT)
        add(MessageMenuAction.DELETE)
    }
}

/** The simplest tier: what a message says can be copied or heard again, nothing more. */
private fun simpleMenu(
    text: String,
    reading: Boolean,
): List<MessageMenuAction> =
    if (text.isBlank()) {
        emptyList()
    } else {
        listOf(MessageMenuAction.COPY, if (reading) MessageMenuAction.STOP_READING else MessageMenuAction.READ)
    }
