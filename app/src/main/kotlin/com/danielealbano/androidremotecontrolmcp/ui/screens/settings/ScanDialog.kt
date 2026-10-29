@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.danielealbano.androidremotecontrolmcp.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The camera, looking for a pairing QR code. What it finds is handed over once, never shown. */
@Composable
internal fun ScanDialog(
    onFound: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.computers_scan_how), style = MaterialTheme.typography.bodyMedium)
                val square = Modifier.padding(vertical = 12.dp).fillMaxWidth().aspectRatio(1f)
                QrCamera(onFound, square.clip(MaterialTheme.shapes.large))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_rename_cancel)) }
            }
        }
    }
}

@Composable
private fun QrCamera(
    onFound: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(Unit) {
        val worker = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(worker) { image ->
            val text = image.use { QrFrames.read(QrFrames.greyOf(it), it.width, it.height) }
            val first = text != null && done.compareAndSet(false, true)
            if (first) ContextCompat.getMainExecutor(context).execute { onFound(text.orEmpty()) }
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider =
                future.get().also {
                    val preview = Preview.Builder().build().apply { surfaceProvider = view.surfaceProvider }
                    runCatching { it.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                        .onFailure { e -> Log.w("MCP:PairScan", "camera unavailable: ${e.javaClass.simpleName}") }
                }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            worker.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
