package com.danielealbano.androidremotecontrolmcp.services.localmodel

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which models were added: each has a folder under [root] holding its files and a `model.json`
 * describing it, so removing the folder forgets the model too.
 */
class LocalModelStore(
    private val root: () -> File,
) {
    fun folder(id: String): File = File(root(), id)

    /** Every added model, by name; a folder left by a built-in model is adopted on the way. */
    fun models(): List<LocalModelSpec> {
        LocalModelCatalog.LEGACY.forEach { adopt(it) }
        val dirs = root().listFiles().orEmpty().filter { it.isDirectory }
        return dirs.mapNotNull { read(File(it, SPEC)) }.sortedBy { it.name }
    }

    fun find(id: String): LocalModelSpec? = read(File(folder(id), SPEC))

    /**
     * Records [spec] so it shows in the list and can be downloaded. Adding the same files again
     * returns the model already there; another model with the same id gets a numbered id.
     */
    fun add(spec: LocalModelSpec): LocalModelSpec {
        val taken = models()
        val same = taken.firstOrNull { it.weights.url == spec.weights.url && it.mmproj?.url == spec.mmproj?.url }
        if (same != null) return same
        val ids = taken.map { it.id }.toSet()
        val id =
            generateSequence(1) { it + 1 }
                .map { n -> if (n == 1) spec.id else "${spec.id}-$n" }
                .first { it !in ids }
        return spec.copy(id = id).also { write(it) }
    }

    /** Removes the model's folder: its description and its files, partial ones too. */
    fun forget(id: String) {
        folder(id).deleteRecursively()
    }

    private fun write(spec: LocalModelSpec) {
        val file = File(folder(spec.id).apply { mkdirs() }, SPEC)
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(LocalModelSpec.serializer(), spec))
        check(temp.renameTo(file) || (file.delete() && temp.renameTo(file))) { "保存模型信息失败" }
    }

    private fun read(file: File): LocalModelSpec? =
        file
            .takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(LocalModelSpec.serializer(), it.readText()) }.getOrNull() }

    /** A built-in model's folder from before `model.json` existed, holding its weights. */
    private fun adopt(spec: LocalModelSpec) {
        val dir = folder(spec.id)
        if (!File(dir, SPEC).exists() && File(dir, spec.weights.name).exists()) write(spec)
    }

    private companion object {
        const val SPEC = "model.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
