package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.DiscoveredUi

/** What the character import sheet can ask for. */
class ImportActions(
    val onClose: () -> Unit,
    val onRequestPermission: () -> Unit,
    val onRescan: () -> Unit,
    val onImport: (DiscoveredUi) -> Unit,
)
