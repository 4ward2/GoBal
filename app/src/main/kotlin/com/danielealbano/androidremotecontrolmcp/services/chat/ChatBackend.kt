package com.danielealbano.androidremotecontrolmcp.services.chat

import javax.inject.Inject
import javax.inject.Singleton

/** Everything the chat screen talks to, bundled so view models stay within a handful of dependencies. */
@Singleton
class ChatBackend
    @Inject
    constructor(
        val gateway: ModelGateway,
        val loopback: PhoneMcpLoopbackClient,
        val store: ConversationStore,
        val editor: ConversationEditor,
        val attachments: AttachmentStore,
        val presence: PresenceMonitor,
    )
