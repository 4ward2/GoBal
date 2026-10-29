package com.danielealbano.androidremotecontrolmcp.ui.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Where the camera app is asked to put a photo taken for the chat. */
object CameraShots {
    /** One reused file: the chat makes its own shrunk copy as soon as the photo comes back. */
    const val FILE_NAME = "camera_shot.jpg"

    /**
     * A content URI the camera app can write to, inside the chat's attachment folder (the only
     * folder the app's file provider exposes).
     */
    fun target(context: Context): Uri {
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, FILE_NAME))
    }
}
