package com.danielealbano.androidremotecontrolmcp.ui.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Hands an exported conversation to whichever app the user picks. */
object ConversationSharing {
    private const val MARKDOWN = "text/markdown"

    fun share(
        context: Context,
        file: File,
    ) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(MARKDOWN)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
            .onFailure { Toast.makeText(context, "没有可以接收的应用", Toast.LENGTH_SHORT).show() }
    }
}
