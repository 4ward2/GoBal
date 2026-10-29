package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.localmodel.HubHttp
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelServer
import com.danielealbano.androidremotecontrolmcp.services.localmodel.ModelHub
import com.danielealbano.androidremotecontrolmcp.services.localmodel.ModelHubClient
import com.danielealbano.androidremotecontrolmcp.services.localmodel.ModelImportLogicTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ModelImportViewModel")
class ModelImportViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val server = mockk<LocalModelServer> { every { totalMemory() } returns (12L shl 30) }
    private val listed = mutableListOf<String>()
    private val headers = mutableListOf<String>()
    private var listBody =
        """
        [{"type":"file","size":400,"path":"m-Q2_K.gguf"},
         {"type":"file","size":500,"path":"m-Q4_K_M.gguf"},
         {"type":"file","size":100,"path":"mmproj-m-Q8_0.gguf"}]
        """.trimIndent()
    private lateinit var viewModel: ModelImportViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val hubs =
            ModelHubClient().apply {
                http =
                    object : HubHttp {
                        override fun text(url: String): Pair<Int, String> {
                            listed += url
                            return 200 to listBody
                        }

                        override fun prefix(
                            url: String,
                            bytes: Int,
                        ): Pair<Int, ByteArray> {
                            headers += url
                            return 206 to ModelImportLogicTest.gguf()
                        }
                    }
            }
        viewModel = ModelImportViewModel(hubs, server, dispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun settle() = dispatcher.scheduler.advanceUntilIdle()

    @Test
    fun `the hub is chosen first, then a pasted link lists the files and suggests one`() {
        viewModel.open()
        assertNull(viewModel.ui.value.hub)
        viewModel.chooseHub(ModelHub.HF_MIRROR)
        viewModel.editLink("https://hf-mirror.com/o/m-GGUF")
        assertEquals(RepoListing.Loading, viewModel.ui.value.listing)
        settle()

        val ui = viewModel.ui.value
        assertEquals(listOf("https://hf-mirror.com/api/models/o/m-GGUF/tree/main?recursive=true"), listed)
        assertEquals("m-Q4_K_M.gguf", ui.weights?.path)
        assertEquals(listOf("https://hf-mirror.com/o/m-GGUF/resolve/main/m-Q4_K_M.gguf"), headers)
        assertFalse(ui.readingHeader)
        val plan = ui.plan!!
        assertEquals(3072, plan.contextSize)
        assertEquals("mmproj-m-Q8_0.gguf", plan.mmproj?.name)
        assertFalse(ui.tooBig)
    }

    @Test
    fun `typing waits for a pause and only the last link is read`() {
        viewModel.open()
        viewModel.chooseHub(ModelHub.HF_MIRROR)
        "o/m-GGUF".forEachIndexed { i, _ -> viewModel.editLink("o/m-GGUF".take(i + 1)) }
        settle()
        assertEquals(listOf("https://hf-mirror.com/api/models/o/m-GGUF/tree/main?recursive=true"), listed)
    }

    @Test
    fun `a file named in the link is picked, and switching files re-reads the header`() {
        viewModel.open()
        viewModel.chooseHub(ModelHub.MODELSCOPE)
        listBody = """{"Code":200,"Data":{"Files":[{"Type":"blob","Path":"a-Q2_K.gguf","Size":4},""" +
            """{"Type":"blob","Path":"a-Q4_0.gguf","Size":5}]}}"""
        viewModel.editLink("https://modelscope.cn/models/o/a/file/view/master/a-Q2_K.gguf")
        settle()
        assertEquals(
            "a-Q2_K.gguf",
            viewModel.ui.value.weights
                ?.path,
        )
        val other = (viewModel.ui.value.listing as RepoListing.Ready).models.weights[1]
        viewModel.chooseWeights(other)
        settle()
        assertEquals("https://modelscope.cn/models/o/a/resolve/master/a-Q4_0.gguf", headers.last())
        assertEquals(
            "a-Q4_0.gguf",
            viewModel.ui.value.plan
                ?.weights
                ?.name,
        )
    }

    @Test
    fun `vision can be left out, and a model larger than the phone is refused`() {
        every { server.totalMemory() } returns 1000
        viewModel.open()
        viewModel.chooseHub(ModelHub.HF_MIRROR)
        viewModel.editLink("o/m")
        settle()
        viewModel.setVision(false)
        assertNull(
            viewModel.ui.value.plan
                ?.mmproj,
        )
        assertTrue(viewModel.ui.value.tooBig)
    }

    @Test
    fun `a ModelScope link switches the hub, nonsense clears the list, closing forgets everything`() {
        viewModel.open()
        viewModel.chooseHub(ModelHub.HF_MIRROR)
        viewModel.editLink("https://modelscope.cn/models/o/m")
        assertEquals(ModelHub.MODELSCOPE, viewModel.ui.value.hub)
        viewModel.editLink("not a link")
        settle()
        assertEquals(RepoListing.Idle, viewModel.ui.value.listing)
        assertTrue(listed.isEmpty())
        viewModel.close()
        assertFalse(viewModel.ui.value.open)
        assertEquals("", viewModel.ui.value.link)
    }

    @Test
    fun `a failed listing is shown in words`() {
        listBody = """[{"type":"file","size":5,"path":"model.safetensors"}]"""
        viewModel.open()
        viewModel.chooseHub(ModelHub.HF_MIRROR)
        viewModel.editLink("o/m")
        settle()
        val failed = viewModel.ui.value.listing as RepoListing.Failed
        assertTrue(failed.message.contains("GGUF"))
        assertNull(viewModel.ui.value.plan)
    }
}
