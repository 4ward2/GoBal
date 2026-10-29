@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.ToolCallDetail

@Composable
fun ToolDetailView(
    detail: ToolCallDetail,
    modifier: Modifier = Modifier,
) {
    SelectionContainer(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                ).padding(12.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Column {
            when (detail) {
                is ToolCallDetail.Shell -> ShellDetailContent(detail)
                is ToolCallDetail.Read -> ReadDetailContent(detail)
                is ToolCallDetail.Edit -> EditDetailContent(detail)
                is ToolCallDetail.Write -> WriteDetailContent(detail)
                is ToolCallDetail.Search -> SearchDetailContent(detail)
                is ToolCallDetail.Fetch -> FetchDetailContent(detail)
                is ToolCallDetail.SubAgent -> SubAgentDetailContent(detail)
                else -> GenericDetailContent(detail)
            }
        }
    }
}

@Composable
private fun ShellDetailContent(detail: ToolCallDetail.Shell) {
    DetailRow("命令：", detail.command)
    if (!detail.cwd.isNullOrEmpty()) DetailRow("目录：", detail.cwd)
    if (!detail.output.isNullOrEmpty()) DetailRow("输出：", detail.output)
    if (detail.exitCode != null) DetailRow("返回码：", detail.exitCode.toString())
}

@Composable
private fun ReadDetailContent(detail: ToolCallDetail.Read) {
    DetailRow("路径：", detail.filePath)
    if (!detail.content.isNullOrEmpty()) DetailRow("内容：", detail.content)
}

@Composable
private fun EditDetailContent(detail: ToolCallDetail.Edit) {
    DetailRow("路径：", detail.filePath)
    if (!detail.oldString.isNullOrEmpty()) DetailRow("原文：", detail.oldString)
    if (!detail.newString.isNullOrEmpty()) DetailRow("新文：", detail.newString)
    if (!detail.unifiedDiff.isNullOrEmpty()) DetailRow("diff：", detail.unifiedDiff)
}

@Composable
private fun WriteDetailContent(detail: ToolCallDetail.Write) {
    DetailRow("路径：", detail.filePath)
    if (!detail.content.isNullOrEmpty()) DetailRow("内容：", detail.content)
}

@Composable
private fun SearchDetailContent(detail: ToolCallDetail.Search) {
    DetailRow("查询：", detail.query)
    if (!detail.content.isNullOrEmpty()) DetailRow("结果：", detail.content)
    if (detail.numMatches != null) DetailRow("匹配数：", detail.numMatches.toString())
}

@Composable
private fun FetchDetailContent(detail: ToolCallDetail.Fetch) {
    DetailRow("URL：", detail.url)
    if (!detail.result.isNullOrEmpty()) DetailRow("结果：", detail.result)
    if (detail.code != null) DetailRow("状态码：", detail.code.toString())
}

@Composable
private fun SubAgentDetailContent(detail: ToolCallDetail.SubAgent) {
    if (!detail.subAgentType.isNullOrEmpty()) DetailRow("类型：", detail.subAgentType)
    if (!detail.log.isNullOrEmpty()) DetailRow("日志：", detail.log)
}

@Composable
private fun GenericDetailContent(detail: ToolCallDetail) {
    Text(
        text = detail.summary,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
fun DetailRow(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}
