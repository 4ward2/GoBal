package com.danielealbano.androidremotecontrolmcp.services.localmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@DisplayName("Adding a local model from a link")
class ModelImportLogicTest {
    @Nested
    @DisplayName("ModelLinks")
    inner class Links {
        @Test
        fun `repository pages, file pages and bare names are understood`() {
            val hf = ModelHub.HF_MIRROR
            val bare = ModelLinks.parse("zai-org/glm-edge-4b-chat-gguf", hf)
            assertEquals(ModelLink(hf, "zai-org/glm-edge-4b-chat-gguf"), bare)
            assertEquals(ModelLink(hf, "org/repo"), ModelLinks.parse("https://hf-mirror.com/org/repo/", hf))
            val inText = ModelLinks.parse("看这个 https://hf-mirror.com/org/repo/tree/main", hf)
            assertEquals(ModelLink(hf, "org/repo"), inText)
            assertEquals(
                ModelLink(hf, "org/repo", "sub/m-Q4_K_M.gguf"),
                ModelLinks.parse("https://huggingface.co/org/repo/blob/main/sub/m-Q4_K_M.gguf", hf),
            )
            assertEquals(
                ModelLink(hf, "org/repo", "m.gguf"),
                ModelLinks.parse("https://hf-mirror.com/org/repo/resolve/main/m.gguf?download=true", hf),
            )
        }

        @Test
        fun `ModelScope links, with or without models and file paths`() {
            val ms = ModelHub.MODELSCOPE
            val files = ModelLinks.parse("https://www.modelscope.cn/models/ZhipuAI/glm/files", ms)
            assertEquals(ModelLink(ms, "ZhipuAI/glm"), files)
            assertEquals(
                ModelLink(ms, "ZhipuAI/glm", "a.gguf"),
                ModelLinks.parse("https://modelscope.cn/models/ZhipuAI/glm/file/view/master/a.gguf", ms),
            )
        }

        @Test
        fun `a link from the other family switches the hub, within Hugging Face the choice stays`() {
            fun hubOf(
                link: String,
                chosen: ModelHub,
            ) = ModelLinks.parse(link, chosen)?.hub
            assertEquals(ModelHub.MODELSCOPE, hubOf("https://modelscope.cn/models/a/b", ModelHub.HF_MIRROR))
            assertEquals(ModelHub.HUGGING_FACE, hubOf("https://huggingface.co/a/b", ModelHub.MODELSCOPE))
            assertEquals(ModelHub.HF_MIRROR, hubOf("https://huggingface.co/a/b", ModelHub.HF_MIRROR))
            assertEquals(ModelHub.HUGGING_FACE, hubOf("https://hf-mirror.com/a/b", ModelHub.HUGGING_FACE))
        }

        @Test
        fun `other sites, half links and plain words are not links`() {
            val hub = ModelHub.HF_MIRROR
            assertNull(ModelLinks.parse("https://github.com/a/b", hub))
            assertNull(ModelLinks.parse("https://hf-mirror.com/a", hub))
            assertNull(ModelLinks.parse("qwen", hub))
            assertNull(ModelLinks.parse("", hub))
        }

        @Test
        fun `each hub lists and downloads from its own paths`() {
            assertEquals(
                "https://hf-mirror.com/api/models/a/b/tree/main?recursive=true",
                ModelHub.HF_MIRROR.listUrl("a/b"),
            )
            assertEquals(
                "https://modelscope.cn/api/v1/models/a/b/repo/files?Revision=master&Recursive=true",
                ModelHub.MODELSCOPE.listUrl("a/b"),
            )
            val spaced = ModelHub.HUGGING_FACE.fileUrl("a/b", "d/x y.gguf")
            assertEquals("https://huggingface.co/a/b/resolve/main/d/x%20y.gguf", spaced)
            val scope = ModelHub.MODELSCOPE.fileUrl("a/b", "x.gguf")
            assertEquals("https://modelscope.cn/models/a/b/resolve/master/x.gguf", scope)
        }
    }

    @Nested
    @DisplayName("RepoFiles")
    inner class Files {
        private val hfList =
            """
            [{"type":"file","size":2075,"path":".gitattributes"},
             {"type":"directory","size":0,"path":"sub"},
             {"type":"file","size":900,"path":"sub/m-Q8_0.gguf"},
             {"type":"file","size":500,"path":"m-Q4_K_M.gguf","lfs":{"size":500}},
             {"type":"file","size":400,"path":"m-Q2_K.gguf"},
             {"type":"file","size":200,"path":"mmproj-m-f16.gguf"},
             {"type":"file","size":100,"path":"mmproj-m-Q8_0.gguf"},
             {"type":"file","size":9000,"path":"big-00001-of-00002.gguf"}]
            """.trimIndent()

        private val msList =
            """
            {"Code":200,"Data":{"Files":[
              {"Type":"tree","Path":"sub","Size":0},
              {"Type":"blob","Path":"ggml-model-Q4_0.gguf","Size":2485562304,"IsLFS":true},
              {"Type":"blob","Path":"README.md","Size":1046}]}}
            """.trimIndent()

        @Test
        fun `both list formats become files, folders are left out`() {
            assertEquals(7, RepoFiles.parse(ModelHub.HF_MIRROR, hfList).size)
            assertEquals(
                listOf(RepoFile("ggml-model-Q4_0.gguf", 2485562304), RepoFile("README.md", 1046)),
                RepoFiles.parse(ModelHub.MODELSCOPE, msList),
            )
        }

        @Test
        fun `weights and projectors are split, smallest first, without shards`() {
            val models = RepoFiles.models(RepoFiles.parse(ModelHub.HF_MIRROR, hfList))
            assertEquals(listOf("m-Q2_K.gguf", "m-Q4_K_M.gguf", "sub/m-Q8_0.gguf"), models.weights.map { it.path })
            assertEquals(listOf("mmproj-m-Q8_0.gguf", "mmproj-m-f16.gguf"), models.projectors.map { it.path })
        }

        @Test
        fun `Q4_K_M is suggested, then Q4_0, otherwise the smallest, and projectors prefer 8-bit`() {
            val models = RepoFiles.models(RepoFiles.parse(ModelHub.HF_MIRROR, hfList))
            assertEquals("m-Q4_K_M.gguf", RepoFiles.preferred(models.weights)?.path)

            fun pick(vararg names: String) = RepoFiles.preferred(names.mapIndexed { i, n -> RepoFile(n, i + 1L) })?.path
            assertEquals("a-Q4_0.gguf", pick("a-Q2_K.gguf", "a-Q4_0.gguf"))
            assertEquals("a-f16.gguf", pick("a-f16.gguf", "a-Q8_0.gguf"))
            assertNull(RepoFiles.preferred(emptyList()))
            assertEquals("mmproj-m-Q8_0.gguf", RepoFiles.preferredProjector(models.projectors)?.path)
        }

        @Test
        fun `quantisation tags are read from file names`() {
            assertEquals("Q4_K_M", RepoFiles.quantOf("Qwen3-4B-Q4_K_M.gguf"))
            assertEquals("IQ4_XS", RepoFiles.quantOf("dir/Qwen3-4B-UD-IQ4_XS.gguf"))
            assertEquals("BF16", RepoFiles.quantOf("Qwen3-4B-BF16.gguf"))
            assertEquals("Q4_0", RepoFiles.quantOf("ggml-model-q4_0.gguf"))
            assertNull(RepoFiles.quantOf("model.gguf"))
        }
    }

    @Nested
    @DisplayName("GgufHeader")
    inner class Header {
        @Test
        fun `the model's shape is read and reading stops at the tokenizer`() {
            val info = GgufHeader.read(gguf())!!
            assertEquals("chatglm", info.architecture)
            assertEquals(3072, info.contextLength)
            assertEquals(40, info.blocks)
            assertEquals(6, info.headsKv)
            // 40 layers x 3072 tokens x 6 heads x (128 + 128) x 2 bytes
            assertEquals(377_487_360L, info.kvCacheBytes(3072))
        }

        @Test
        fun `a prefix cut in the middle keeps what came before`() {
            val full = gguf()
            val info = GgufHeader.read(full.copyOf(full.size - 20))!!
            assertEquals(3072, info.contextLength)
        }

        @Test
        fun `explicit key and value lengths win over the embedding split`() {
            val info = GgufHeader.read(gguf(arch = "gemma3", keyLength = 256))!!
            assertEquals(40L * 100 * 6 * (256 + 256) * 2, info.kvCacheBytes(100))
        }

        @Test
        fun `other files and unknown shapes give nothing`() {
            assertNull(GgufHeader.read("not a gguf file at all".toByteArray()))
            assertNull(GgufHeader.read(ByteArray(3)))
            assertNull(GgufInfo("x", 10, null, 1, 1, 1, null, null).kvCacheBytes(10))
        }
    }

    @Nested
    @DisplayName("LocalModelPlan")
    inner class Plan {
        private val shape = GgufInfo("llama", 131_072, 28, 32, 8, 3072, null, null)

        @Test
        fun `names and ids come from the repository and the quantisation`() {
            val file = RepoFile("sub/Qwen3-4B-Q4_K_M.gguf", 10)
            assertEquals("Qwen3-4B · Q4_K_M", LocalModelPlan.nameOf("unsloth/Qwen3-4B-GGUF", file))
            assertEquals("qwen3-4b-q4_k_m", LocalModelPlan.idOf("Qwen3-4B · Q4_K_M"))
            val plain = LocalModelPlan.nameOf("zai-org/glm-edge-4b-chat-gguf", RepoFile("model.gguf", 1))
            assertEquals("glm-edge-4b-chat · model", plain)
        }

        @Test
        fun `long contexts are capped, unknown ones guessed`() {
            assertEquals(LocalModelPlan.MAX_CONTEXT, LocalModelPlan.contextFor(shape))
            assertEquals(2048, LocalModelPlan.contextFor(shape.copy(contextLength = 2048)))
            assertEquals(LocalModelPlan.UNKNOWN_CONTEXT, LocalModelPlan.contextFor(null))
            assertEquals(LocalModelPlan.UNKNOWN_CONTEXT, LocalModelPlan.contextFor(shape.copy(contextLength = 0)))
        }

        @Test
        fun `the spec downloads both files from the hub and budgets for them and the cache`() {
            val weights = RepoFile("m-Q4_K_M.gguf", 1000)
            val projector = RepoFile("mmproj-m-Q8_0.gguf", 100)
            val spec = LocalModelPlan.spec(ModelHub.MODELSCOPE, "o/m-GGUF", weights, projector, shape)
            assertEquals("m-q4_k_m", spec.id)
            assertEquals("https://modelscope.cn/models/o/m-GGUF/resolve/master/m-Q4_K_M.gguf", spec.weights.url)
            assertEquals("mmproj-m-Q8_0.gguf", spec.mmproj?.name)
            assertEquals(1100, spec.downloadBytes)
            assertEquals(ModelHub.MODELSCOPE.name, spec.hub)
            val cache = shape.kvCacheBytes(LocalModelPlan.MAX_CONTEXT)!!
            assertTrue(spec.ramBytes > 1100 + cache)
            val unknown = LocalModelPlan.spec(ModelHub.MODELSCOPE, "o/m", weights, null, null)
            assertNull(unknown.mmproj)
            assertTrue(unknown.ramBytes > 1000)
        }
    }

    @Nested
    @DisplayName("ModelHubClient")
    inner class Client {
        private fun newClient(
            code: Int = 200,
            body: String = "[]",
            fail: Boolean = false,
            prefix: ByteArray = gguf(),
        ): ModelHubClient =
            ModelHubClient().apply {
                http =
                    object : HubHttp {
                        override fun text(url: String): Pair<Int, String> {
                            if (fail) throw IOException("down")
                            return code to body
                        }

                        override fun prefix(
                            url: String,
                            bytes: Int,
                        ): Pair<Int, ByteArray> = 206 to prefix
                    }
            }

        private val link = ModelLink(ModelHub.HF_MIRROR, "a/b")

        @Test
        fun `a repository with GGUF files lists them`() {
            val body = """[{"type":"file","size":5,"path":"b-Q4_0.gguf"}]"""
            assertEquals(listOf(RepoFile("b-Q4_0.gguf", 5)), newClient(body = body).list(link).getOrThrow().weights)
        }

        @Test
        fun `failures are explained in words`() {
            val missing =
                newClient(code = 401)
                    .list(link)
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
            assertTrue(missing.contains("找不到"), missing)
            val noGguf = newClient(body = """[{"type":"file","size":5,"path":"model.safetensors"}]""").list(link)
            assertTrue(
                noGguf
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("GGUF"),
            )
            val blocked =
                newClient(fail = true)
                    .list(link.copy(hub = ModelHub.HUGGING_FACE))
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
            assertTrue(blocked.contains("HF 镜像"), blocked)
            assertFalse(newClient(code = 500).list(link).isSuccess)
        }

        @Test
        fun `the header is read from the file's first bytes`() {
            assertEquals(3072, newClient().header("https://x/y.gguf")?.contextLength)
            assertNull(newClient(prefix = ByteArray(0)).header("https://x/y.gguf"))
        }
    }

    companion object {
        /** A GGUF v3 header shaped like GLM-Edge 4B, followed by a tokenizer key that must not be read. */
        fun gguf(
            arch: String = "chatglm",
            keyLength: Int? = null,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val keys =
                mutableListOf<Pair<String, (ByteArrayOutputStream) -> Unit>>(
                    "general.architecture" to { o -> str(o, 8, arch) },
                    "general.languages" to { o -> strings(o, listOf("zh", "en")) },
                    "$arch.context_length" to { o -> u32(o, 3072) },
                    "$arch.embedding_length" to { o -> u32(o, 3072) },
                    "$arch.block_count" to { o -> u32(o, 40) },
                    "$arch.attention.head_count" to { o -> u32(o, 24) },
                    "$arch.attention.head_count_kv" to { o -> u32(o, 6) },
                    "$arch.rope.freq_base" to { o -> f32(o, 10000f) },
                    "tokenizer.ggml.tokens" to { o -> strings(o, listOf("a", "b")) },
                )
            keyLength?.let { k ->
                keys.add(2, "$arch.attention.key_length" to { o -> u32(o, k) })
                keys.add(2, "$arch.attention.value_length" to { o -> u32(o, k) })
            }
            out.write(le(4).putInt(0x46554747).array())
            out.write(le(4).putInt(3).array())
            out.write(le(8).putLong(0).array())
            out.write(le(8).putLong(keys.size.toLong()).array())
            keys.forEach { (name, writeValue) ->
                raw(out, name)
                writeValue(out)
            }
            return out.toByteArray()
        }

        private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

        private fun raw(
            o: ByteArrayOutputStream,
            s: String,
        ) {
            val bytes = s.toByteArray()
            o.write(le(8).putLong(bytes.size.toLong()).array())
            o.write(bytes)
        }

        private fun str(
            o: ByteArrayOutputStream,
            type: Int,
            s: String,
        ) {
            o.write(le(4).putInt(type).array())
            raw(o, s)
        }

        private fun u32(
            o: ByteArrayOutputStream,
            v: Int,
        ) {
            o.write(le(4).putInt(4).array())
            o.write(le(4).putInt(v).array())
        }

        private fun f32(
            o: ByteArrayOutputStream,
            v: Float,
        ) {
            o.write(le(4).putInt(6).array())
            o.write(le(4).putFloat(v).array())
        }

        private fun strings(
            o: ByteArrayOutputStream,
            values: List<String>,
        ) {
            o.write(le(4).putInt(9).array())
            o.write(le(4).putInt(8).array())
            o.write(le(8).putLong(values.size.toLong()).array())
            values.forEach { raw(o, it) }
        }
    }
}
