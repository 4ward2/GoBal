package com.danielealbano.androidremotecontrolmcp.utils

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * The Android level of the phone, for code that has to take an older road on older phones.
 *
 * The platform stub that unit tests run against reports level 0. That is read as "the newest
 * Android": tests exercise the current behaviour unless they ask for an older phone with
 * [pretend], which is the only reason [pretend] exists.
 */
object ApiLevel {
    @Volatile
    private var pretended: Int? = null

    val current: Int
        get() = pretended ?: Build.VERSION.SDK_INT.takeIf { it > 0 } ?: Int.MAX_VALUE

    @ChecksSdkIntAtLeast(parameter = 0)
    fun atLeast(level: Int): Boolean = current >= level

    /** For tests: behave as a phone of [level] until called with null. */
    fun pretend(level: Int?) {
        pretended = level
    }
}
