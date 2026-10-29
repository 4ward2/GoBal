package com.danielealbano.androidremotecontrolmcp.services.character

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** A character card found on the phone that can be imported with one tap. */
data class DiscoveredCard(
    val uri: Uri,
    val fileName: String,
    val card: CharacterCard,
    val bytes: ByteArray,
    val alreadyImported: Boolean,
)

/**
 * Finds character cards the user already has on the phone, so importing is one tap instead of a
 * trip through a file manager: every PNG the media store knows (Downloads, Pictures, chat apps'
 * folders) is opened and checked for an embedded card. Small enough to do on demand.
 *
 * Needs the images permission; JSON cards outside the app cannot be listed this way on Android 13+
 * (the images permission does not cover them), for those the share sheet is the way in.
 */
class CharacterFinder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: CharacterRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        val permission: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        fun hasPermission(): Boolean {
            val state = ContextCompat.checkSelfPermission(context, permission)
            return state == PackageManager.PERMISSION_GRANTED
        }

        suspend fun scan(): List<DiscoveredCard> =
            withContext(ioDispatcher) {
                if (hasPermission()) {
                    repository.load()
                    candidates().mapNotNull { (uri, name) -> read(uri, name) }
                } else {
                    emptyList()
                }
            }

        private fun candidates(): List<Pair<Uri, String>> {
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Images.Media.MIME_TYPE} = ? AND ${MediaStore.Images.Media.SIZE} <= ?"
            val args = arrayOf("image/png", MAX_BYTES.toString())
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            val found = mutableListOf<Pair<Uri, String>>()
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext() && found.size < MAX_CANDIDATES) {
                    val uri = Uri.withAppendedPath(collection, c.getLong(idCol).toString())
                    found += uri to c.getString(nameCol).orEmpty()
                }
            }
            return found
        }

        private fun read(
            uri: Uri,
            name: String,
        ): DiscoveredCard? {
            val bytes = readCapped(context.contentResolver, uri)
            val parsed = bytes?.let { CharacterCardParser.parse(it) }
            return parsed?.let { p ->
                val imported = repository.characters.value.any { it.id == p.card.id }
                DiscoveredCard(uri, name, p.card, bytes, imported)
            }
        }

        private fun readCapped(
            resolver: ContentResolver,
            uri: Uri,
        ): ByteArray? =
            try {
                resolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    bytes.takeIf { it.size <= MAX_BYTES && CharacterCardParser.isPng(it) }
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }

        private companion object {
            const val MAX_BYTES = 8L * 1024 * 1024
            const val MAX_CANDIDATES = 400
        }
    }
