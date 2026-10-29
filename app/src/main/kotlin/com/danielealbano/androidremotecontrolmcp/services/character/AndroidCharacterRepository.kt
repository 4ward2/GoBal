package com.danielealbano.androidremotecontrolmcp.services.character

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Cards live in `files/characters/`; avatars are stored as small PNG thumbnails. */
@Singleton
class AndroidCharacterRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : FileCharacterRepository(File(context.filesDir, "characters"), ioDispatcher, AvatarThumbnail)

/**
 * Card PNGs are often 1–2 MB portraits; the chat only ever shows them at avatar size, so they are
 * shrunk once at import to at most [MAX_SIDE] px. Undecodable images mean "no avatar".
 */
object AvatarThumbnail : AvatarThumbnailer {
    private const val MAX_SIDE = 256
    private const val PNG_QUALITY = 100

    override fun shrink(png: ByteArray): ByteArray? {
        val sample = sampleSize(png)
        val bitmap =
            sample?.let { s ->
                BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = s })
            }
        return bitmap?.let { encodePng(it) }
    }

    /** The power-of-two downsample that lands the longer side under [MAX_SIDE]; null if unreadable. */
    fun sampleSize(
        png: ByteArray,
        maxSide: Int = MAX_SIDE,
    ): Int? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return if (longest > 0) sample else null
    }

    private fun encodePng(bitmap: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        bitmap.recycle()
        return if (ok) out.toByteArray() else null
    }
}
