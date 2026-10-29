package com.danielealbano.androidremotecontrolmcp.services.skill

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The user's saved skills. Names are unique ignoring case, spaces and punctuation. */
interface SkillRepository {
    val skills: StateFlow<List<Skill>>

    suspend fun load()

    fun find(name: String): Skill? {
        val key = SkillMatcher.key(name)
        return skills.value.firstOrNull { SkillMatcher.key(it.name) == key }
    }

    /**
     * Stores [skill] after [SkillValidator]. A skill with the same name is replaced only when
     * [replace] is set (saving an identical skill again is always fine). [previousName] renames.
     */
    suspend fun save(
        skill: Skill,
        replace: Boolean,
        previousName: String? = null,
    ): Result<Skill>

    suspend fun delete(name: String): Boolean

    suspend fun recordRun(
        name: String,
        ok: Boolean,
    )
}

open class FileSkillRepository(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : SkillRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Skill.serializer())
    private val mutex = Mutex()
    private var loaded = false

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    override val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    override suspend fun load() {
        mutex.withLock { ensureLoaded() }
    }

    override suspend fun save(
        skill: Skill,
        replace: Boolean,
        previousName: String?,
    ): Result<Skill> =
        mutex.withLock {
            ensureLoaded()
            val problems = SkillValidator.problems(skill)
            val key = SkillMatcher.key(skill.name)
            val oldKey = previousName?.let(SkillMatcher::key) ?: key
            val existing = _skills.value.firstOrNull { SkillMatcher.key(it.name) == key }
            val renamedOver = existing != null && oldKey != key
            val same =
                existing != null &&
                    existing.copy(updatedAt = 0, createdAt = 0) == skill.copy(updatedAt = 0, createdAt = 0)
            when {
                problems.isNotEmpty() -> {
                    Result.failure(IllegalArgumentException(problems.joinToString("；")))
                }

                existing != null && (renamedOver || (!replace && !same)) -> {
                    Result.failure(IllegalStateException("已经有叫「${existing.name}」的技能了"))
                }

                else -> {
                    val previous = _skills.value.firstOrNull { SkillMatcher.key(it.name) == oldKey }
                    val now = clock()
                    val stored =
                        skill.copy(
                            createdAt = previous?.createdAt?.takeIf { it > 0 } ?: now,
                            updatedAt = now,
                            runCount = previous?.runCount ?: skill.runCount,
                            lastRunOk = previous?.lastRunOk ?: skill.lastRunOk,
                        )
                    val rest =
                        _skills.value.filterNot {
                            SkillMatcher.key(it.name) == oldKey || SkillMatcher.key(it.name) == key
                        }
                    persist(rest + stored)
                    Result.success(stored)
                }
            }
        }

    override suspend fun delete(name: String): Boolean =
        mutex.withLock {
            ensureLoaded()
            val key = SkillMatcher.key(name)
            val rest = _skills.value.filterNot { SkillMatcher.key(it.name) == key }
            val removed = rest.size != _skills.value.size
            if (removed) persist(rest)
            removed
        }

    override suspend fun recordRun(
        name: String,
        ok: Boolean,
    ) {
        mutex.withLock {
            ensureLoaded()
            val key = SkillMatcher.key(name)
            val updated =
                _skills.value.map {
                    if (SkillMatcher.key(it.name) == key) it.copy(runCount = it.runCount + 1, lastRunOk = ok) else it
                }
            if (updated != _skills.value) persist(updated)
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        _skills.value =
            withContext(ioDispatcher) {
                if (!file.isFile) return@withContext emptyList()
                try {
                    json.decodeFromString(serializer, file.readText())
                } catch (_: SerializationException) {
                    setAsideUnreadable()
                } catch (_: IllegalArgumentException) {
                    setAsideUnreadable()
                }
            }
        loaded = true
    }

    /** Keeps an unreadable file as `.broken` instead of letting the next save overwrite it. */
    private fun setAsideUnreadable(): List<Skill> {
        file.renameTo(File(file.parentFile, "${file.name}.broken"))
        return emptyList()
    }

    /** Write-then-rename with fsync, so a power cut leaves the old or the new list, never half of one. */
    private suspend fun persist(list: List<Skill>) {
        withContext(ioDispatcher) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(serializer, list).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("could not write ${file.name}")
            }
        }
        _skills.value = list
    }
}

/** Skills live in `files/skills/skills.json`. */
@Singleton
class AndroidSkillRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : FileSkillRepository(File(File(context.filesDir, "skills"), "skills.json"), ioDispatcher)
