package com.danielealbano.androidremotecontrolmcp.services.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.memory.ContentNames
import com.danielealbano.androidremotecontrolmcp.services.memory.DocumentReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a picked picture or document into `files/attachments/` so the chat owns it, and extracts
 * what the model will read: pictures are shrunk to [MAX_IMAGE_SIDE] px JPEG (vision models cap
 * image size anyway), documents go through [DocumentReader].
 */
@Singleton
class AttachmentStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val reader: DocumentReader,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val dir = File(context.filesDir, "attachments")

        suspend fun importImage(uri: Uri): Result<Attachment> =
            withContext(ioDispatcher) {
                runCatching {
                    val bitmap = decodeShrunk(uri)
                    val file = newFile("jpg")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    bitmap.recycle()
                    // The on-device model is text-only, so a picture's words are read out for it now;
                    // vision models get the picture itself as well.
                    val words = reader.readImage(file)?.text.orEmpty()
                    val name = ContentNames.displayName(context, uri, "图片")
                    Attachment(Attachment.IMAGE, name, file.path, "image/jpeg", words)
                }
            }

        suspend fun importDocument(uri: Uri): Result<Attachment> =
            withContext(ioDispatcher) {
                reader.read(uri).mapCatching { doc ->
                    val file = newFile(doc.name.substringAfterLast('.', "bin").lowercase())
                    ContentNames.open(context, uri).use { input -> file.outputStream().use { input.copyTo(it) } }
                    Attachment(Attachment.FILE, doc.name, file.path, doc.mime, doc.extracted.text)
                }
            }

        private fun decodeShrunk(uri: Uri): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ContentNames.open(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = sampleSize(maxOf(bounds.outWidth, bounds.outHeight))
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = ContentNames.open(context, uri).use { BitmapFactory.decodeStream(it, null, options) }
            return requireNotNull(bitmap) { "图片无法解码" }
        }

        private fun newFile(extension: String): File {
            dir.mkdirs()
            return File(dir, "${UUID.randomUUID()}.$extension")
        }

        private fun sampleSize(longest: Int): Int {
            var sample = 1
            while (longest / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
            return sample
        }

        private companion object {
            const val MAX_IMAGE_SIDE = 1600
            const val JPEG_QUALITY = 88
        }
    }
