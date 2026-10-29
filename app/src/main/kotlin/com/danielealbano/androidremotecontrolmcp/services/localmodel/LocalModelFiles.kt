package com.danielealbano.androidremotecontrolmcp.services.localmodel

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Opens [url] from byte [from] on; returns the status code and the body. */
fun interface RangeOpener {
    fun open(
        url: String,
        from: Long,
    ): Pair<Int, InputStream>
}

/**
 * The models on the phone: where their files live, whether they are complete, and fetching them.
 * [store] knows which models were added. Downloads resume: bytes already received stay in a
 * ".part" file and only the rest is asked for. A file counts as installed only when its size
 * matches the description exactly.
 */
@Singleton
class LocalModelFiles
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: AssistSettingsRepository,
    ) {
        /** Download progress (0..1) of models being fetched, by model id. */
        private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
        val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

        var opener: RangeOpener = UrlConnectionRangeOpener

        val store = LocalModelStore { File(context.getExternalFilesDir(null) ?: context.filesDir, "models") }

        fun dir(spec: LocalModelSpec): File = store.folder(spec.id).apply { mkdirs() }

        fun installed(spec: LocalModelSpec): Boolean = spec.files.all { File(dir(spec), it.name).length() == it.bytes }

        fun installedModels(): List<LocalModelSpec> = store.models().filter { installed(it) }

        /** Fetches every missing file of [spec]; cancelling the coroutine keeps the ".part" files. */
        suspend fun download(spec: LocalModelSpec): Result<Unit> =
            runCatching {
                val dir = dir(spec)
                var done = spec.files.filter { File(dir, it.name).length() == it.bytes }.sumOf { it.bytes }
                report(spec, done)
                for (file in spec.files) {
                    val target = File(dir, file.name)
                    if (target.length() == file.bytes) continue
                    val before = done
                    fetch(file, target) { got -> report(spec, before + got) }
                    done += file.bytes
                }
                syncProvider()
            }.also { _progress.update { it - spec.id } }

        /** Forgets [spec] and removes its files (partial ones too). */
        suspend fun delete(spec: LocalModelSpec) {
            store.forget(spec.id)
            syncProvider()
        }

        /**
         * Keeps the "本机模型" service in 设置 → 服务商 in step with what is installed, so contacts can
         * pick an installed model like any other; it disappears when nothing is installed.
         */
        suspend fun syncProvider() {
            val models = installedModels()
            if (models.isEmpty()) {
                if (settings.providers.first().any { it.id == LocalModels.PROVIDER_ID }) {
                    settings.deleteProvider(LocalModels.PROVIDER_ID)
                }
                return
            }
            settings.saveProvider(
                ModelProvider(
                    id = LocalModels.PROVIDER_ID,
                    name = LocalModels.PROVIDER_NAME,
                    kind = ProviderKind.OPENAI,
                    baseUrl = ServerLaunch.BASE_URL,
                    models = models.map { it.id },
                    modelContexts = models.associate { it.id to it.contextSize },
                ),
            )
        }

        private fun report(
            spec: LocalModelSpec,
            bytes: Long,
        ) {
            _progress.update { it + (spec.id to (bytes.toFloat() / spec.downloadBytes).coerceIn(0f, 1f)) }
        }

        private suspend fun fetch(
            file: LocalModelFile,
            target: File,
            onBytes: (Long) -> Unit,
        ) {
            val part = File(target.path + PART)
            if (part.length() > file.bytes) part.delete()
            val (code, body) = opener.open(file.url, part.length())
            // 200 means the server ignored the range and is sending the whole file again.
            val append = code == HTTP_PARTIAL
            check(code == HTTP_OK || append) { downloadError(code) }
            val start = if (append) part.length() else 0L
            body.use { input -> FileOutputStream(part, append).use { out -> copy(input, out, start, onBytes) } }
            check(part.length() == file.bytes) { "下载不完整，已保留进度，可以继续下载" }
            check(part.renameTo(target)) { "保存文件失败" }
        }
    }

private const val PART = ".part"
private const val BUFFER = 256 * 1024
private const val HTTP_OK = 200
private const val HTTP_PARTIAL = 206
private const val HTTP_ERROR = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

private fun downloadError(code: Int): String =
    when (code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> "这个模型要登录并同意协议才能下载，换一个仓库试试"
        HTTP_NOT_FOUND -> "文件已经不在这个仓库里了"
        else -> "下载失败（HTTP $code）"
    }

/** Copies until the body ends; cancelling the coroutine stops between chunks. */
private suspend fun copy(
    input: InputStream,
    out: FileOutputStream,
    start: Long,
    onBytes: (Long) -> Unit,
) {
    val buffer = ByteArray(BUFFER)
    var got = start
    var n = input.read(buffer)
    while (n >= 0) {
        currentCoroutineContext().ensureActive()
        out.write(buffer, 0, n)
        got += n
        onBytes(got)
        n = input.read(buffer)
    }
}

/** [RangeOpener] over [HttpURLConnection]; the hubs redirect https to https, which it follows. */
object UrlConnectionRangeOpener : RangeOpener {
    private const val CONNECT_MS = 15_000
    private const val READ_MS = 60_000

    override fun open(
        url: String,
        from: Long,
    ): Pair<Int, InputStream> {
        val conn =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
        val code = conn.responseCode
        val stream = if (code >= HTTP_ERROR) conn.errorStream else conn.inputStream
        return code to (stream ?: ByteArrayInputStream(ByteArray(0)))
    }
}
