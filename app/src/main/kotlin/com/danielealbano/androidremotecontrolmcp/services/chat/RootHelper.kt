package com.danielealbano.androidremotecontrolmcp.services.chat

import java.io.File

/** Whether this phone can have the root helper at all: without an `su` binary there is none to talk to. */
object RootHelper {
    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")

    fun present(): Boolean = SU_PATHS.any { File(it).exists() }
}
