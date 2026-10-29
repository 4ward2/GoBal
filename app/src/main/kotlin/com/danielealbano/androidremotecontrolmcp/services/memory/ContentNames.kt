package com.danielealbano.androidremotecontrolmcp.services.memory

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/** Small helpers for content URIs picked through the system file / photo pickers. */
object ContentNames {
    /** The name the picker showed for [uri], or [fallback] when the provider gives none. */
    fun displayName(
        context: Context,
        uri: Uri,
        fallback: String,
    ): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        val fromProvider =
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: fallback
    }

    fun open(
        context: Context,
        uri: Uri,
    ): InputStream = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开文件" }
}
