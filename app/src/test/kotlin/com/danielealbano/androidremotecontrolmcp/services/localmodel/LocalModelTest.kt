package com.danielealbano.androidremotecontrolmcp.services.localmodel

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

@DisplayName("Local models")
class LocalModelTest {
    private val tiny =
        LocalModelSpec(
            id = "tiny",
            name = "Tiny",
            summary = "",
            weights = LocalModelFile("w.gguf", "https://hf-mirror.com/repo/w.gguf", 10),
            mmproj = LocalModelFile("p.gguf", "https://hf-mirror.com/repo/p.gguf", 4),
            contextSize = 2048,
            ramBytes = 1000,
        )

    @Nested
    @DisplayName("ServerLaunch")
    inner class Launch {
        @Test
        fun `the command serves on loopback with a small prompt cache, idle sleep and the projector`() {
            val args = ServerLaunch.command(File("/lib/x"), tiny, File("/m"), threads = 4)
            assertEquals(File("/lib/x").path, args.first())

            fun value(flag: String) = args[args.indexOf(flag) + 1]
            assertEquals("127.0.0.1", value("--host"))
            assertEquals("8093", value("--port"))
            assertEquals(File("/m", "w.gguf").path, value("--model"))
            assertEquals(File("/m", "p.gguf").path, value("--mmproj"))
            assertEquals("tiny", value("--alias"))
            assertEquals("256", value("--cache-ram"))
            assertEquals("300", value("--sleep-idle-seconds"))
            assertTrue("--no-webui" in args)
        }

        @Test
        fun `a model without a projector gets no mmproj flag`() {
            assertFalse("--mmproj" in ServerLaunch.command(File("/x"), tiny.copy(mmproj = null), File("/m"), 2))
        }

        @Test
        fun `threads stay between two and four`() {
            assertEquals(2, ServerLaunch.threadsFor(1))
            assertEquals(4, ServerLaunch.threadsFor(8))
            assertEquals(4, ServerLaunch.threadsFor(16))
        }

        @Test
        fun `too little memory is explained, enough passes`() {
            assertNull(ServerLaunch.memoryShortfall(tiny, 1000))
            assertTrue(ServerLaunch.memoryShortfall(tiny, 999)!!.startsWith("内存不够"))
        }

        @Test
        fun `catalog sizes add up`() {
            val edge = LocalModelCatalog.GLM_EDGE_4B
            assertEquals(2_485_562_304, edge.downloadBytes)
            assertEquals(3072, edge.contextSize)
            assertEquals(
                "https://hf-mirror.com/zai-org/glm-edge-4b-chat-gguf/resolve/main/ggml-model-Q4_0.gguf",
                edge.weights.url,
            )
            // The weights at least stay resident.
            LocalModelCatalog.LEGACY.forEach { assertTrue(it.ramBytes >= it.weights.bytes, it.id) }
        }
    }

    @Nested
    @DisplayName("LocalModelFiles")
    inner class Files {
        @TempDir
        lateinit var root: File

        private val settings = mockk<AssistSettingsRepository>(relaxed = true)

        private fun newFiles(): LocalModelFiles {
            val context = mockk<Context> { every { getExternalFilesDir(null) } returns root }
            return LocalModelFiles(context, settings)
        }

        @Test
        fun `a download resumes from what arrived and is installed only when complete`() =
            runTest {
                every { settings.providers } returns flowOf(emptyList())
                val f = newFiles()
                val dir = f.dir(tiny)
                File(dir, "w.gguf.part").writeBytes(ByteArray(6) { 1 })
                val asked = mutableListOf<Pair<String, Long>>()
                f.opener =
                    RangeOpener { url, from ->
                        asked += url to from
                        val size = if (url.endsWith("w.gguf")) 10 else 4
                        206 to ByteArrayInputStream(ByteArray((size - from).toInt()) { 2 })
                    }
                assertFalse(f.installed(tiny))

                assertTrue(f.download(tiny).isSuccess)

                val mirror = "https://hf-mirror.com/repo"
                val expected = listOf("$mirror/w.gguf" to 6L, "$mirror/p.gguf" to 0L)
                assertEquals(expected, asked)
                assertEquals(10, File(dir, "w.gguf").length())
                assertTrue(f.installed(tiny))
                assertTrue(f.progress.value.isEmpty())
            }

        @Test
        fun `a server that ignores the range sends everything again and the part is rewritten`() =
            runTest {
                every { settings.providers } returns flowOf(emptyList())
                val f = newFiles()
                File(f.dir(tiny), "w.gguf.part").writeBytes(ByteArray(3))
                f.opener =
                    RangeOpener { url, _ ->
                        val size = if (url.endsWith("w.gguf")) 10 else 4
                        200 to ByteArrayInputStream(ByteArray(size))
                    }
                assertTrue(f.download(tiny).isSuccess)
                assertEquals(10, File(f.dir(tiny), "w.gguf").length())
            }

        @Test
        fun `a short download fails, keeps its progress and installs nothing`() =
            runTest {
                val f = newFiles()
                f.opener = RangeOpener { _, _ -> 206 to ByteArrayInputStream(ByteArray(2)) }
                assertTrue(f.download(tiny).isFailure)
                assertEquals(2, File(f.dir(tiny), "w.gguf.part").length())
                assertFalse(f.installed(tiny))
            }

        @Test
        fun `the on-device service is removed when no catalog model is installed`() =
            runTest {
                val f = newFiles()
                val onPhone = ModelProvider(LocalModels.PROVIDER_ID, "本机模型", baseUrl = ServerLaunch.BASE_URL)
                every { settings.providers } returns flowOf(listOf(onPhone))
                f.syncProvider()
                coVerify { settings.deleteProvider(LocalModels.PROVIDER_ID) }
                coVerify(exactly = 0) { settings.saveProvider(any()) }
            }

        @Test
        fun `deleting a model removes its files, partial ones too, and forgets it`() =
            runTest {
                every { settings.providers } returns flowOf(emptyList())
                val f = newFiles()
                f.store.add(tiny)
                File(f.dir(tiny), "w.gguf.part").writeBytes(ByteArray(3))
                f.delete(tiny)
                assertFalse(File(root, "models/tiny").exists())
                assertNull(f.store.find("tiny"))
            }

        @Test
        fun `an added model is listed and found, and survives a new instance`() {
            newFiles().store.add(tiny)
            val again = newFiles()
            assertEquals(listOf(tiny), again.store.models())
            assertEquals(tiny, again.store.find("tiny"))
            assertNull(again.store.find("other"))
            assertTrue(again.installedModels().isEmpty())
        }

        @Test
        fun `adding the same files twice keeps one model, another model with the same id is numbered`() {
            val f = newFiles()
            assertEquals("tiny", f.store.add(tiny).id)
            assertEquals("tiny", f.store.add(tiny.copy(name = "again")).id)
            val other = tiny.copy(weights = tiny.weights.copy(url = "https://hf-mirror.com/other/w.gguf"))
            assertEquals("tiny-2", f.store.add(other).id)
            assertEquals(2, f.store.models().size)
        }

        @Test
        fun `a folder left by a built-in model is adopted, an empty one is not`() {
            val edge = LocalModelCatalog.GLM_EDGE_4B
            File(root, "models/${edge.id}").mkdirs()
            val f = newFiles()
            assertTrue(f.store.models().isEmpty())
            File(root, "models/${edge.id}/${edge.weights.name}").writeBytes(ByteArray(1))
            assertEquals(listOf(edge), f.store.models())
        }

        @Test
        fun `a broken description is skipped`() {
            File(root, "models/bad").mkdirs()
            File(root, "models/bad/model.json").writeText("{not json")
            assertTrue(newFiles().store.models().isEmpty())
        }

        @Test
        fun `a login-only file says so`() =
            runTest {
                val f = newFiles()
                f.opener = RangeOpener { _, _ -> 401 to ByteArrayInputStream(ByteArray(0)) }
                val error =
                    f
                        .download(tiny)
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                assertTrue(error.contains("登录"), error)
            }

        @Test
        fun `installed models become the on-device service with their context windows`() =
            runTest {
                every { settings.providers } returns flowOf(emptyList())
                val f = newFiles()
                f.store.add(tiny)
                File(f.dir(tiny), "w.gguf").writeBytes(ByteArray(10))
                File(f.dir(tiny), "p.gguf").writeBytes(ByteArray(4))
                f.syncProvider()
                coVerify {
                    settings.saveProvider(
                        match {
                            val listed = it.models == listOf("tiny") && it.modelContexts["tiny"] == 2048
                            it.id == LocalModels.PROVIDER_ID && listed
                        },
                    )
                }
            }
    }
}
