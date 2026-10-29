package com.danielealbano.androidremotecontrolmcp.services.skill

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.AccessibilityFreshWindowReader
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TargetDisplay
import com.danielealbano.androidremotecontrolmcp.services.shadow.ShadowDisplayClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/** [SkillDevice] over the accessibility service; follows [TargetDisplay] like every MCP tool. */
class AndroidSkillDevice
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val windowReader: AccessibilityFreshWindowReader,
        private val actionExecutor: ActionExecutor,
        private val shadowClient: ShadowDisplayClient,
        private val screenText: ScreenTextReader,
    ) : SkillDevice {
        override fun read(): MultiWindowResult =
            try {
                // Skills only ever act on what is on screen: skipping hidden subtrees keeps reads fast
                // on long lists, where scrolled-away rows stay mounted.
                windowReader.readVisible()
            } catch (e: McpToolException.PermissionDenied) {
                throw IllegalStateException("无障碍服务没有开启", e)
            } catch (_: McpToolException.ActionFailed) {
                // No window roots while an activity transition is running: read again shortly.
                MultiWindowResult(emptyList())
            }

        override suspend fun click(
            nodeId: String,
            snapshot: MultiWindowResult,
        ): Result<Unit> = actionExecutor.clickNode(nodeId, snapshot.windows)

        override suspend fun tap(
            x: Float,
            y: Float,
        ): Result<Unit> = actionExecutor.tap(x, y)

        override suspend fun setText(
            nodeId: String,
            text: String,
            snapshot: MultiWindowResult,
        ): Result<Unit> = actionExecutor.setTextOnNode(nodeId, text, snapshot.windows)

        override suspend fun scroll(direction: SkillScrollDirection): Result<Unit> =
            actionExecutor.scroll(
                if (direction == SkillScrollDirection.DOWN) ScrollDirection.DOWN else ScrollDirection.UP,
            )

        override suspend fun back(): Result<Unit> = actionExecutor.pressBack()

        override suspend fun launch(
            packageName: String,
            uri: String?,
        ): Result<Unit> =
            when {
                !TargetDisplay.isDefault -> {
                    val params =
                        buildMap {
                            put("display_id", JsonPrimitive(TargetDisplay.id))
                            put("package", JsonPrimitive(packageName))
                            if (!uri.isNullOrBlank()) put("uri", JsonPrimitive(uri))
                        }
                    shadowClient.request("open", params).map { }
                }

                !uri.isNullOrBlank() -> {
                    start(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(packageName))
                }

                else -> {
                    // A skill starts from the app's first screen: without clearing the task the app
                    // comes back wherever it was left (美团 reopens its search page), and step 1 misses.
                    context.packageManager
                        .getLaunchIntentForPackage(packageName)
                        ?.let { start(it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
                        ?: Result.failure(IllegalArgumentException("$packageName has no launcher activity"))
                }
            }

        private fun start(intent: Intent): Result<Unit> =
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Result.success(Unit)
            } catch (e: ActivityNotFoundException) {
                Result.failure(e)
            } catch (e: SecurityException) {
                Result.failure(e)
            }

        override suspend fun ocrScreen(): String? = screenText.read()

        override suspend fun ocrLocate(text: String): OcrHit? = screenText.locate(text)

        override suspend fun findImage(step: SkillStep.Image): OcrHit? = screenText.findImage(step)
    }
