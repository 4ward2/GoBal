package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("StorageLabels")
class StorageLabelsTest {
    private fun location(
        id: String,
        name: String,
    ) = StorageLocation(id = id, name = name, path = "/x", description = "", treeUri = "", availableBytes = null)

    @Test
    fun `built-in locations read in Chinese with their access scope`() {
        assertEquals("图片 · 全部文件", StorageLabels.of(location("builtin:pictures", "Pictures - All files")))
        assertEquals("图片 · 只有本应用创建的文件", StorageLabels.of(location("builtin:pictures", "Pictures - Only owned files")))
        assertEquals("下载 · 只有本应用创建的文件", StorageLabels.of(location("builtin:downloads", "Downloads - Only owned files")))
    }

    @Test
    fun `locations the user added keep their own name`() {
        assertEquals("项目文件", StorageLabels.of(location("saf:1", "项目文件")))
    }
}
