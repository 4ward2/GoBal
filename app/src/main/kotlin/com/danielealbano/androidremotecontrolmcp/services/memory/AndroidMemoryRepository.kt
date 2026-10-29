package com.danielealbano.androidremotecontrolmcp.services.memory

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The memory library in `files/memory/`. Before anything is remembered the personal vault is
 * read, so that its values are stored as their placeholders; with the vault unreadable nothing
 * is replaced and nothing is lost, the text is kept as written.
 */
@Singleton
class AndroidMemoryLibrary
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        vault: VaultRepository,
    ) : MemoryLibrary(
            File(context.filesDir, "memory"),
            ioDispatcher,
            MemoryGuard { text ->
                runCatching { vault.load() }
                vault.redactor.value.redact(text)
            },
        )

/** A contact's own memory files, as a view of the library. */
@Singleton
class AndroidMemoryRepository
    @Inject
    constructor(
        library: AndroidMemoryLibrary,
    ) : FileMemoryRepository(library)
