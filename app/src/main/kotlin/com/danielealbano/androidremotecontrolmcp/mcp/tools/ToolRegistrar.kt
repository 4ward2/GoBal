package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import com.danielealbano.androidremotecontrolmcp.services.camera.CameraProvider
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkService
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentInbox
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import javax.inject.Inject

/** Reading the screen: the tree, its text form and screenshots. */
class ScreenToolDeps
    @Inject
    constructor(
        val treeParser: AccessibilityTreeParser,
        val accessibility: AccessibilityServiceProvider,
        val screenCapture: ScreenCaptureProvider,
        val treeFormatter: CompactTreeFormatter,
        val screenshotAnnotator: ScreenshotAnnotator,
        val screenshotEncoder: ScreenshotEncoder,
    )

/** Finding controls and acting on them. */
class NodeToolDeps
    @Inject
    constructor(
        val nodeCache: AccessibilityNodeCache,
        val snapshotCache: ScreenStateSnapshotCache,
        val webViewMerger: WebViewNodeMerger,
        val elementFinder: ElementFinder,
        val actionExecutor: ActionExecutor,
        val typeInput: TypeInputController,
    )

/** Files, apps, camera, intents, notifications and location. */
class DeviceToolDeps
    @Inject
    constructor(
        val storageLocations: StorageLocationProvider,
        val fileOperations: FileOperationProvider,
        val appManager: AppManager,
        val camera: CameraProvider,
        val intents: IntentDispatcher,
        val notifications: NotificationProvider,
    )

/** The tool groups that are classes of their own. */
class FeatureToolDeps
    @Inject
    constructor(
        val location: LocationProvider,
        val shadowDisplay: ShadowDisplayTools,
        val vault: VaultTools,
        val systemAutofill: SystemAutofillTools,
        val skills: SkillTools,
        val tasks: TaskTools,
    )

/**
 * Sharing files out of the phone, the journal that archives finished tasks as skills, and the
 * tap by words that journal records as a replayable step.
 */
class SharingToolDeps
    @Inject
    constructor(
        val inbox: SharedContentInbox,
        val fileLinks: EphemeralFileLinkService,
        val recorder: ActionRecorder,
        val textTap: TextTapTools,
    )

/** How one server wants its tools: their name prefix, what the user switched off, and its own address. */
class ToolSetup(
    val namePrefix: String,
    val permissions: ToolPermissionsConfig,
    val fileSizeLimitMb: Int,
    /** The externally reachable base URL, for links to shared files. */
    val baseUrl: () -> String,
)

/**
 * Registers every phone tool on an MCP [Server]. The network-facing server and the in-app agent
 * each build their own [Server] from this one list, so a tool can never exist for one and be
 * missing for the other.
 */
class ToolRegistrar
    @Inject
    constructor(
        private val screen: ScreenToolDeps,
        private val node: NodeToolDeps,
        private val device: DeviceToolDeps,
        private val feature: FeatureToolDeps,
        private val sharing: SharingToolDeps,
    ) {
        fun registerAll(
            server: Server,
            setup: ToolSetup,
        ) {
            registerScreenAndNodeTools(server, setup)
            registerDeviceTools(server, setup)
            registerFeatureTools(server, setup)
            // Last: every tool registered so far is wrapped so successful tasks can be archived as skills.
            journalTools(server, setup.namePrefix, sharing.recorder)
        }

        private fun registerScreenAndNodeTools(
            server: Server,
            setup: ToolSetup,
        ) {
            val prefix = setup.namePrefix
            val perms = setup.permissions
            registerScreenIntrospectionTools(
                server,
                screen.treeParser,
                screen.accessibility,
                screen.screenCapture,
                screen.treeFormatter,
                screen.screenshotAnnotator,
                screen.screenshotEncoder,
                node.nodeCache,
                node.snapshotCache,
                node.webViewMerger,
                prefix,
                perms,
            )
            registerSystemActionTools(server, node.actionExecutor, screen.accessibility, prefix, perms)
            registerTouchActionTools(server, node.actionExecutor, prefix, perms)
            registerGestureTools(server, node.actionExecutor, prefix, perms)
            registerNodeActionTools(
                server,
                screen.treeParser,
                node.elementFinder,
                node.actionExecutor,
                screen.accessibility,
                node.nodeCache,
                prefix,
                perms,
            )
            registerTextInputTools(
                server,
                screen.treeParser,
                node.actionExecutor,
                screen.accessibility,
                node.typeInput,
                node.nodeCache,
                prefix,
                perms,
            )
            registerUtilityTools(
                server,
                screen.treeParser,
                node.elementFinder,
                screen.accessibility,
                node.nodeCache,
                prefix,
                perms,
            )
        }

        private fun registerDeviceTools(
            server: Server,
            setup: ToolSetup,
        ) {
            val prefix = setup.namePrefix
            val perms = setup.permissions
            registerFileTools(server, device.storageLocations, device.fileOperations, prefix, perms)
            registerAppManagementTools(server, device.appManager, prefix, perms)
            registerCameraTools(server, device.camera, device.fileOperations, prefix, perms)
            registerIntentTools(server, device.intents, prefix, perms)
            registerNotificationTools(server, device.notifications, prefix, perms)
            registerLocationTools(server, feature.location, prefix, perms)
        }

        private fun registerFeatureTools(
            server: Server,
            setup: ToolSetup,
        ) {
            val prefix = setup.namePrefix
            val perms = setup.permissions
            registerShadowDisplayTools(server, feature.shadowDisplay, prefix, perms)
            registerVaultTools(server, feature.vault, prefix, perms)
            registerSystemAutofillTools(server, feature.systemAutofill, prefix, perms)
            registerSkillTools(server, feature.skills, prefix, perms)
            registerTaskTools(server, feature.tasks, prefix, perms)
            registerTaskGoto(server, feature.tasks, prefix, perms)
            registerTextTapTools(server, sharing.textTap, prefix, perms)
            registerSharingTools(
                server,
                sharing.inbox,
                sharing.fileLinks,
                device.fileOperations,
                setup.fileSizeLimitMb,
                setup.baseUrl,
                prefix,
                perms,
            )
        }
    }
