package com.danielealbano.androidremotecontrolmcp.services.skill

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/** Pixels for [value] dp on this context's screen. */
internal fun Context.dpPx(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

/** A filled, rounded background for the recording overlays' panels. */
internal fun Context.roundedPanel(
    color: Int,
    radiusDp: Int,
) = GradientDrawable().apply {
    cornerRadius = dpPx(radiusDp).toFloat()
    setColor(color)
}
