package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Reads a QR code out of a camera frame's brightness plane. Pure apart from the decoder it wraps. */
object QrFrames {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    /** The text of the QR code in a [width] x [height] grey image, or null when there is none. */
    fun read(
        grey: ByteArray,
        width: Int,
        height: Int,
    ): String? {
        val source = PlanarYUVLuminanceSource(grey, width, height, 0, 0, width, height, false)
        val found = runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints) }
        return found.getOrNull()?.text
    }

    /** The brightness plane of a camera frame without the padding some cameras add to each row. */
    fun greyOf(image: ImageProxy): ByteArray {
        val plane = image.planes[0]
        val out = ByteArray(image.width * image.height)
        val row = ByteArray(plane.rowStride)
        val buffer = plane.buffer
        repeat(image.height) { y ->
            buffer.position(y * plane.rowStride)
            buffer.get(row, 0, minOf(plane.rowStride, buffer.remaining()))
            row.copyInto(out, y * image.width, 0, image.width)
        }
        return out
    }
}
