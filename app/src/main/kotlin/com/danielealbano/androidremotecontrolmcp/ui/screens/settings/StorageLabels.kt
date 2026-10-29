package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation

/**
 * The name a storage location is shown under. Built-in locations keep English names in the data
 * layer because MCP tools report them to the model; on screen they read in Chinese. Locations the
 * user added show the name they were given.
 */
object StorageLabels {
    fun of(location: StorageLocation): String {
        val entry = BuiltinStorageLocation.entries.firstOrNull { it.locationId == location.id }
        return if (entry == null) location.name else "${folderOf(entry)} · ${scopeOf(entry, location)}"
    }

    private fun folderOf(entry: BuiltinStorageLocation): String =
        when (entry) {
            BuiltinStorageLocation.DOWNLOADS -> "下载"
            BuiltinStorageLocation.PICTURES -> "图片"
            BuiltinStorageLocation.MOVIES -> "影片"
            BuiltinStorageLocation.MUSIC -> "音乐"
        }

    /** "All files" once the media permission is granted; otherwise only what this app created. */
    private fun scopeOf(
        entry: BuiltinStorageLocation,
        location: StorageLocation,
    ): String {
        val all = entry.displayNameAll != entry.displayNameOwned && location.name == entry.displayNameAll
        return if (all) "全部文件" else "只有本应用创建的文件"
    }
}
