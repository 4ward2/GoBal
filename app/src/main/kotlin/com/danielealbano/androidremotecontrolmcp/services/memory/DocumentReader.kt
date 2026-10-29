package com.danielealbano.androidremotecontrolmcp.services.memory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A document opened from a content URI: its display name, MIME type and extracted text. */
data class ReadDocument(
    val name: String,
    val mime: String,
    val extracted: Extracted,
)

/**
 * Reads the text of a user-picked document: plain text and Markdown as they are, HTML and DOCX
 * through [TextExtractors], PDF through PDFBox with OCR for pages that have no text layer, and
 * pictures through OCR. Legacy binary `.doc` is refused with a hint to save as DOCX or PDF.
 */
@Singleton
class DocumentReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val ocr: OcrEngine,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun read(uri: Uri): Result<ReadDocument> =
            withContext(ioDispatcher) {
                runCatching {
                    val name = ContentNames.displayName(context, uri, "文件")
                    val mime = context.contentResolver.getType(uri).orEmpty()
                    ReadDocument(name, mime, extract(uri, name, mime))
                }
            }

        /** Text of an image already stored by the app (chat attachments). */
        suspend fun readImage(file: File): Extracted? =
            withContext(ioDispatcher) {
                val bitmap = BitmapFactory.decodeFile(file.path)
                bitmap?.let { ocr.recognize(it) }?.let { Extracted(it, ocr = true) }
            }

        private suspend fun extract(
            uri: Uri,
            name: String,
            mime: String,
        ): Extracted {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == TextExtractors.DOCX_EXTENSION || mime == MIME_DOCX -> {
                    open(uri).use(TextExtractors::docx)
                }

                ext in TextExtractors.HTML_EXTENSIONS || mime == "text/html" -> {
                    open(uri).use(TextExtractors::html)
                }

                ext == "pdf" || mime == "application/pdf" -> {
                    pdf(uri)
                }

                mime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> {
                    image(uri)
                }

                ext in LEGACY_OFFICE -> {
                    throw IllegalArgumentException("暂不支持 .$ext，请另存为 .docx 或 PDF 后再添加")
                }

                ext in TextExtractors.PLAIN_EXTENSIONS || mime.startsWith("text/") || mime.isBlank() -> {
                    open(uri).use(TextExtractors::plainText)
                }

                else -> {
                    throw IllegalArgumentException("不支持的文件类型：$mime")
                }
            }
        }

        /** The text layer first; pages that yield almost nothing are rendered and OCR'd. */
        private suspend fun pdf(uri: Uri): Extracted {
            val pages = textLayer(uri).toMutableList()
            val sparse = pages.withIndex().filter { it.value.length < MIN_TEXT_PER_PAGE }.map { it.index }
            var usedOcr = false
            if (sparse.isNotEmpty()) {
                renderPages(uri, sparse.toSet()) { index, bitmap ->
                    ocr.recognize(bitmap)?.takeIf { it.isNotBlank() }?.let {
                        pages[index] = it
                        usedOcr = true
                    }
                }
            }
            return Extracted(TextExtractors.tidy(pages.joinToString("\n\n")), usedOcr)
        }

        private fun textLayer(uri: Uri): List<String> {
            PDFBoxResourceLoader.init(context)
            return open(uri).use { stream ->
                PDDocument.load(stream).use { doc ->
                    val stripper = PDFTextStripper()
                    (1..doc.numberOfPages).map { page ->
                        stripper.startPage = page
                        stripper.endPage = page
                        TextExtractors.tidy(stripper.getText(doc))
                    }
                }
            }
        }

        private suspend fun renderPages(
            uri: Uri,
            indices: Set<Int>,
            onPage: suspend (Int, Bitmap) -> Unit,
        ) {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return
            val renderer = PdfRenderer(descriptor)
            try {
                indices.filter { it < renderer.pageCount }.take(MAX_OCR_PAGES).forEach { index ->
                    val bitmap = renderPage(renderer, index)
                    onPage(index, bitmap)
                    bitmap.recycle()
                }
            } finally {
                renderer.close()
                descriptor.close()
            }
        }

        private fun renderPage(
            renderer: PdfRenderer,
            index: Int,
        ): Bitmap {
            val page = renderer.openPage(index)
            try {
                val scale = OCR_WIDTH.toFloat() / page.width
                val bitmap = Bitmap.createBitmap(OCR_WIDTH, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            } finally {
                page.close()
            }
        }

        private suspend fun image(uri: Uri): Extracted {
            val bitmap = open(uri).use { BitmapFactory.decodeStream(it) }
            requireNotNull(bitmap) { "图片无法解码" }
            val text = ocr.recognize(bitmap) ?: error("OCR 引擎不可用")
            return Extracted(text, ocr = true)
        }

        private fun open(uri: Uri) = ContentNames.open(context, uri)

        private companion object {
            const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            const val MIN_TEXT_PER_PAGE = 20
            const val MAX_OCR_PAGES = 30
            const val OCR_WIDTH = 1600
            val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
            val LEGACY_OFFICE = setOf("doc", "xls", "ppt", "pptx", "xlsx")
        }
    }
