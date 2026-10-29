package com.danielealbano.androidremotecontrolmcp.services.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Unsent text per conversation, kept in one small file so it survives leaving the app. */
class DraftFile(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(key: String): String = read()[key].orEmpty()

    /** Remembers [text] for [key]; blank text forgets the draft. */
    @Synchronized
    fun save(
        key: String,
        text: String,
    ) {
        val drafts = read().toMutableMap()
        if (text.isBlank()) drafts.remove(key) else drafts[key] = text
        AtomicFiles.save(file, json.encodeToString(drafts.toMap()))
    }

    private fun read(): Map<String, String> {
        val raw = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
        return raw
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            .orEmpty()
    }
}

@Singleton
class DraftStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val drafts = DraftFile(File(context.filesDir, "chat_drafts.json"))

        fun load(key: String): String = drafts.load(key)

        fun save(
            key: String,
            text: String,
        ) = drafts.save(key, text)
    }
