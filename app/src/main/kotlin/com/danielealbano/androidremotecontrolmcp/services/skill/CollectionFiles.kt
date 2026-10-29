package com.danielealbano.androidremotecontrolmcp.services.skill

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A list read by a skill, as saved to disk. */
@Serializable
data class CollectedList(
    val skill: String,
    val time: String,
    val items: List<CollectedItem>,
    val lines: List<String>,
)

/**
 * Saves what collect steps read to the app's external files (`Android/data/<pkg>/files/collected/`),
 * reachable over USB / `adb pull` without any storage permission.
 */
class CollectionFiles
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val json = Json { prettyPrint = true }

        /** The saved file's path, or null when there was nothing to save or it could not be written. */
        fun save(result: SkillRunResult): String? {
            if (result.collected.isEmpty()) return null
            val dir = context.getExternalFilesDir(DIR) ?: File(context.filesDir, DIR)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val name = SkillMatcher.key(result.skillName).filter { it.isLetterOrDigit() }.take(NAME_MAX)
            val file = File(dir, "${name.ifEmpty { "skill" }}-$stamp.json")
            val body =
                CollectedList(result.skillName, stamp, result.collected, result.collected.map { it.line })
            return try {
                dir.mkdirs()
                file.writeText(json.encodeToString(CollectedList.serializer(), body))
                file.absolutePath
            } catch (e: IOException) {
                Log.w(TAG, "could not save collected list", e)
                null
            }
        }

        private companion object {
            const val DIR = "collected"
            const val NAME_MAX = 40
            const val TAG = "MCP:Skills"
        }
    }
