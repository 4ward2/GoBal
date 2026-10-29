package com.danielealbano.androidremotecontrolmcp.services.memory

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** A piece of text found in an image, with its box in image pixels. */
data class OcrBox(
    val text: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** Reads the text in an image; null when the engine is unavailable. */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): String?

    /** The text pieces and where they are; null when no engine can locate text. */
    suspend fun locate(bitmap: Bitmap): List<OcrBox>? = null
}

/**
 * OCR for scanned PDFs and pictures. The phone's RapidOCR service (`127.0.0.1:8890`, a chroot
 * process that only exists on the rooted dev phone) is much better at Chinese, so it is used
 * when it answers; otherwise the bundled Tesseract (chi_sim + eng, "fast" models) runs in-process,
 * which works on any phone.
 */
@Singleton
class OcrEngines
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : OcrEngine {
        private val rapid = RapidOcrHttp()
        private val tesseract = TesseractOcr(context)

        override suspend fun recognize(bitmap: Bitmap): String? =
            withContext(ioDispatcher) {
                val remote = if (rapid.isUp()) runCatching { rapid.recognize(bitmap) }.getOrNull() else null
                remote ?: tesseract.recognize(bitmap)
            }

        override suspend fun locate(bitmap: Bitmap): List<OcrBox>? =
            withContext(ioDispatcher) {
                val remote = if (rapid.isUp()) runCatching { rapid.boxes(bitmap) }.getOrNull() else null
                remote ?: tesseract.words(bitmap)
            }
    }

/** `POST /ocr` with raw PNG bytes → `{"ok":true,"items":[{text,x,y,w,h}]}`; lines are rebuilt from boxes. */
class RapidOcrHttp(
    private val baseUrl: String = DEFAULT_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun isUp(): Boolean =
        runCatching {
            val conn = URL("$baseUrl/health").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = HEALTH_TIMEOUT_MS
                conn.readTimeout = HEALTH_TIMEOUT_MS
                conn.responseCode == HttpURLConnection.HTTP_OK
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)

    fun recognize(bitmap: Bitmap): String? = post(bitmap)?.let(::linesOf)

    /** Every detected text piece with its box. */
    fun boxes(bitmap: Bitmap): List<OcrBox>? {
        val found = post(bitmap)?.let(::boxesOf) ?: return null
        return found.map { OcrBox(it.text, it.x, it.y, it.w, it.h) }
    }

    private fun post(bitmap: Bitmap): String? {
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        val conn = URL("$baseUrl/ocr").openConnection() as HttpURLConnection
        val body =
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = HEALTH_TIMEOUT_MS
                conn.readTimeout = OCR_TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "image/png")
                conn.outputStream.use { it.write(png.toByteArray()) }
                val ok = conn.responseCode == HttpURLConnection.HTTP_OK
                if (ok) conn.inputStream.use { it.readBytes().decodeToString() } else null
            } finally {
                conn.disconnect()
            }
        return body
    }

    /** Boxes come in reading order per RapidOCR; rows whose centres are close share a line. */
    fun linesOf(body: String): String? {
        val items = boxesOf(body) ?: return null
        val lines = mutableListOf<MutableList<Box>>()
        items.sortedBy { it.y }.forEach { box ->
            val line = lines.lastOrNull()
            if (line != null && abs(line.first().y - box.y) < maxOf(line.first().h, box.h) / 2) {
                line.add(box)
            } else {
                lines.add(mutableListOf(box))
            }
        }
        return lines.joinToString("\n") { line -> line.sortedBy { it.x }.joinToString(" ") { it.text } }
    }

    private fun boxesOf(body: String): List<Box>? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return (root["items"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject
            val text =
                obj
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            if (obj != null && text != null) Box(text, obj.int("x"), obj.int("y"), obj.int("w"), obj.int("h")) else null
        }
    }

    private data class Box(
        val text: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
    )

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private companion object {
        const val DEFAULT_URL = "http://127.0.0.1:8890"
        const val HEALTH_TIMEOUT_MS = 1500
        const val OCR_TIMEOUT_MS = 60_000
        const val PNG_QUALITY = 100
    }
}

/** Tesseract with the language data copied out of `assets/tessdata` on first use. */
class TesseractOcr(
    private val context: Context,
) {
    private val mutex = Mutex()

    suspend fun recognize(bitmap: Bitmap): String? =
        mutex.withLock {
            val dataDir =
                runCatching { ensureData() }.getOrElse { e ->
                    Log.w(TAG, "tessdata unavailable", e)
                    null
                }
            dataDir?.let { run(it, bitmap) }
        }

    /** Word boxes (a word is one character in Chinese); null when Tesseract cannot run. */
    suspend fun words(bitmap: Bitmap): List<OcrBox>? =
        mutex.withLock {
            val dataDir = runCatching { ensureData() }.getOrNull() ?: return@withLock null
            val api = TessBaseAPI()
            try {
                if (!api.init(dataDir.path, LANGUAGES)) return@withLock null
                api.setImage(bitmap)
                api.utF8Text
                val out = mutableListOf<OcrBox>()
                val words = api.resultIterator ?: return@withLock out
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                words.begin()
                do {
                    val text = words.getUTF8Text(level)?.trim().orEmpty()
                    val r = words.getBoundingRect(level)
                    if (text.isNotEmpty()) out += OcrBox(text, r.left, r.top, r.width(), r.height())
                } while (words.next(level))
                words.delete()
                out
            } finally {
                api.recycle()
            }
        }

    private fun run(
        dataDir: File,
        bitmap: Bitmap,
    ): String? {
        val api = TessBaseAPI()
        try {
            if (!api.init(dataDir.path, LANGUAGES)) return null
            api.setImage(bitmap)
            return api.utF8Text?.let { TextExtractors.tidy(it) }
        } finally {
            api.recycle()
        }
    }

    /**
     * Copies the bundled language files out once per app version. The assets are compressed in
     * the APK, so they cannot be memory-mapped or sized with `openFd`; a version marker decides
     * whether the copy on disk is current.
     */
    private fun ensureData(): File {
        val root = File(context.filesDir, "tesseract")
        val dir = File(root, "tessdata").apply { mkdirs() }
        val marker = File(dir, VERSION_MARKER)
        val version =
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .longVersionCode
                .toString()
        val names = context.assets.list("tessdata").orEmpty()
        val current = marker.isFile && marker.readText() == version && names.all { File(dir, it).length() > 0 }
        if (!current) {
            names.forEach { name -> copyAsset(name, File(dir, name)) }
            marker.writeText(version)
        }
        return root
    }

    private fun copyAsset(
        name: String,
        target: File,
    ) {
        context.assets.open("tessdata/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private companion object {
        const val TAG = "MCP:Ocr"
        const val LANGUAGES = "chi_sim+eng"
        const val VERSION_MARKER = ".version"
    }
}
