@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.DiffLine
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.FileEntry
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.FileContent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_FILE_LINES = 5000
private const val BREADCRUMB_FONT_SIZE = 12
internal const val FILE_CONTENT_HEIGHT = 0.4f
private const val LINE_NUMBER_WIDTH = 0.1f
private const val LINE_NUMBER_FONT_SIZE = 11
private const val CONTENT_FONT_SIZE = 12

/** Code reads best tight: the app's body line height (made for chat text) doubles the gaps. */
private const val CODE_LINE_HEIGHT = 16
internal const val TAB_ROW_SPACING = 1

private const val KIB = 1024L
private const val MIB = KIB * KIB
private const val GIB = MIB * KIB

private val SHORT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** The daemon's ISO time in the phone's own zone, 「10-13 21:01」; as given when it is not ISO. */
private fun shortTime(iso: String): String =
    runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(SHORT_TIME) }.getOrDefault(iso)

/** A size the way people read it: 「12 KB」 rather than a byte count. */
private fun formatFileSize(bytes: Long): String =
    when {
        bytes < KIB -> "$bytes B"
        bytes < MIB -> "${bytes / KIB} KB"
        bytes < GIB -> "${bytes / MIB} MB"
        else -> "${bytes / GIB} GB"
    }

@Composable
internal fun BreadcrumbNavigation(
    path: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parts =
        if (path.isEmpty()) {
            listOf("root")
        } else {
            path.split("/").filter { it.isNotEmpty() }
        }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Text("/", fontSize = BREADCRUMB_FONT_SIZE.sp)
            }
            val targetPath = parts.take(index + 1).joinToString("/")
            Text(
                part,
                fontSize = BREADCRUMB_FONT_SIZE.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .padding(horizontal = 4.dp)
                        .clickable(role = Role.Button) { onNavigate(targetPath) },
            )
        }
    }
}

@Composable
internal fun FileEntryItem(
    entry: FileEntry,
    onTapDirectory: () -> Unit,
    onTapFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(entry.name) },
        supportingContent = {
            val time = shortTime(entry.modified)
            val sized = stringResource(R.string.remote_files_entry_info, formatFileSize(entry.size), time)
            Text(if (entry.isDirectory) time else sized)
        },
        leadingContent = {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription =
                    stringResource(if (entry.isDirectory) R.string.remote_files_folder else R.string.remote_files_file),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier =
            modifier
                .padding(8.dp)
                .clickable(role = Role.Button) {
                    if (entry.isDirectory) onTapDirectory() else onTapFile()
                },
    )
}

@Composable
internal fun FileContentViewer(
    fileContent: FileContent?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (fileContent == null) return

    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                fileContent.path,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.remote_files_close),
                )
            }
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            val lines = fileContent.text.split("\n").take(MAX_FILE_LINES)
            items(lines.size, key = { it }) { index ->
                Row {
                    Text(
                        (index + 1).toString(),
                        modifier =
                            Modifier
                                .padding(end = 8.dp)
                                .fillMaxWidth(LINE_NUMBER_WIDTH),
                        fontSize = LINE_NUMBER_FONT_SIZE.sp,
                        lineHeight = CODE_LINE_HEIGHT.sp,
                        color = MaterialTheme.colorScheme.outline,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        lines[index],
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = CONTENT_FONT_SIZE.sp,
                        lineHeight = CODE_LINE_HEIGHT.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DiffLineView(
    line: DiffLine,
    modifier: Modifier = Modifier,
) {
    val backgroundColor =
        when (line.type) {
            "add" -> MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.1f)
            "remove" -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
            else -> null
        }

    val textColor =
        when (line.type) {
            "add" -> MaterialTheme.colorScheme.surfaceTint
            "remove" -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(backgroundColor ?: MaterialTheme.colorScheme.surface)
                .padding(4.dp),
    ) {
        Text(
            when (line.type) {
                "add" -> "+"
                "remove" -> "-"
                else -> " "
            },
            color = textColor,
            fontSize = CONTENT_FONT_SIZE.sp,
            modifier = Modifier.padding(end = 4.dp),
        )

        Text(
            line.content,
            color = textColor,
            fontSize = CONTENT_FONT_SIZE.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
