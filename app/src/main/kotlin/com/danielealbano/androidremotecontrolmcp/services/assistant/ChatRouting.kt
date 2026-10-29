package com.danielealbano.androidremotecontrolmcp.services.assistant

import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRunner
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryLibrary
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChats
import com.danielealbano.androidremotecontrolmcp.services.speech.MessageReader
import javax.inject.Inject

/**
 * Where a sentence typed in the chat can go besides the model (the local rules, the phone agent),
 * the check of what a new user still has to set up, the voice that reads a message aloud, and the
 * agents on paired computers.
 */
class ChatRouting
    @Inject
    constructor(
        val localFirst: AssistantLocalFirst,
        val agent: AgentRunner,
        val setup: SetupProbe,
        val reader: MessageReader,
        val remote: RemoteChats,
        val memory: MemoryLibrary,
    )
