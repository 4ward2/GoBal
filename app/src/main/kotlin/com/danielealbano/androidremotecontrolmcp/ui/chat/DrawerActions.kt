package com.danielealbano.androidremotecontrolmcp.ui.chat

/** What the side drawer can ask for; hoisted so the drawer stays stateless. */
class DrawerActions(
    val onClear: () -> Unit,
    val onOpenSettings: () -> Unit,
    /** Opens the page of the contact on screen: its portrait and texts, its model, its memory files. */
    val onOpenContact: () -> Unit = {},
    /** Starts a fresh conversation with the current contact. */
    val onNewConversation: () -> Unit = {},
    /** Opens the list of this contact's conversations (with search across all of them). */
    val onConversations: () -> Unit = {},
)
