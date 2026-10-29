@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.call

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.services.call.FrameSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * The camera during a video call: a live preview, and a [FrameSource] that snaps a JPEG of what
 * the camera sees so it can travel with the next turn to a model that can look at pictures.
 */
class CameraFrames(
    private val context: Context,
) : FrameSource {
    private val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    val preview = Preview.Builder().build()
    var front: Boolean = true

    override suspend fun capture(): File? =
        suspendCancellableCoroutine { cont ->
            val file = File(context.cacheDir, "call-frame-${System.currentTimeMillis()}.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) = cont.resume(file)

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "frame capture failed", exception)
                        cont.resume(null)
                    }
                },
            )
        }

    fun useCases(): Array<androidx.camera.core.UseCase> = arrayOf(preview, capture)

    fun selector(): CameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private companion object {
        const val TAG = "MCP:CallCamera"
    }
}

@Composable
fun rememberCameraFrames(): CameraFrames {
    val context = LocalContext.current
    return remember { CameraFrames(context) }
}

/** Binds the camera to this composable's lifecycle and shows its preview. */
@Composable
fun CameraPreview(
    frames: CameraFrames,
    front: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(front) {
        frames.front = front
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider =
                future.get().also {
                    it.unbindAll()
                    frames.preview.surfaceProvider = view.surfaceProvider
                    runCatching { it.bindToLifecycle(owner, frames.selector(), *frames.useCases()) }
                        .onFailure { e -> Log.w("MCP:CallCamera", "bind failed", e) }
                }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
