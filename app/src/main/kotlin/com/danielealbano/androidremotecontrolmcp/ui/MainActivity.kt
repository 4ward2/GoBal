package com.danielealbano.androidremotecontrolmcp.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatHttpService
import com.danielealbano.androidremotecontrolmcp.services.link.RemoteAlerts
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderModelSync
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute
import com.danielealbano.androidremotecontrolmcp.ui.screens.MainScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.PermissionRequests
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AppearanceViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val appearanceViewModel: AppearanceViewModel by viewModels()

    @Inject
    lateinit var remoteAlerts: RemoteAlerts

    @Inject
    lateinit var modelSync: ProviderModelSync

    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var microphonePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var mediaImagesPermissionLauncher: ActivityResultLauncher<String>

    private var requestedTabRoute by mutableStateOf<String?>(null)

    /** Counts grants of the images permission so the character import sheet can rescan. */
    private var mediaPermissionGrants by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        notificationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        cameraPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        microphonePermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        locationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        mediaImagesPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) mediaPermissionGrants++
            }

        requestedTabRoute = tabFromIntent(intent)
        remoteAlerts.opened(intent)
        // Port 8765: the local API of the root helper (started only on phones that have one).
        ChatHttpService.start(applicationContext)

        setContent {
            val appearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()
            AndroidRemoteControlMcpTheme(appearance = appearance) {
                MainScreen(
                    permissionRequests =
                        PermissionRequests(
                            notification = ::requestNotificationPermission,
                            camera = ::requestCameraPermission,
                            microphone = ::requestMicrophonePermission,
                            location = ::requestLocationPermission,
                            mediaImages = ::requestMediaImagesPermission,
                        ),
                    requestedTabRoute = requestedTabRoute,
                    onRequestedTabConsumed = { requestedTabRoute = null },
                    mediaPermissionGrants = mediaPermissionGrants,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        remoteAlerts.opened(intent)
        val tab = tabFromIntent(intent)
        requestedTabRoute = tab
        if (tab == TopLevelRoute.Chat.route) {
            ChatHttpService.start(applicationContext)
        }
    }

    override fun onStart() {
        super.onStart()
        // Coming to the front is when a model may be chosen: the lists offered are the providers' own.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { modelSync.refresh() } }
        viewModel.startServerIfIdle(this)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStatus(this)
    }

    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun requestMicrophonePermission() {
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun requestMediaImagesPermission() {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        mediaImagesPermissionLauncher.launch(permission)
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"

        fun tabFromIntent(intent: Intent?): String? {
            val raw =
                intent
                    ?.getStringExtra(EXTRA_OPEN_TAB)
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
            // "ideas" was the 灵感 tab, removed; its old deep links land on the chat.
            return when (raw) {
                TopLevelRoute.Chat.route, "xiaoai", "assistant", "ideas" -> TopLevelRoute.Chat.route
                TopLevelRoute.Server.route -> TopLevelRoute.Server.route
                TopLevelRoute.Settings.route -> TopLevelRoute.Settings.route
                TopLevelRoute.About.route -> TopLevelRoute.About.route
                TopLevelRoute.Skills.route -> TopLevelRoute.Skills.route
                SettingsRoute.Shell.route -> SettingsRoute.Shell.route
                else -> null
            }
        }
    }
}
