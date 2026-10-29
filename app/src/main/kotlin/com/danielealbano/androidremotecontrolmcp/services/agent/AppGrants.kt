package com.danielealbano.androidremotecontrolmcp.services.agent

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.Display
import android.view.inputmethod.InputMethodManager
import com.danielealbano.androidremotecontrolmcp.services.chat.AtomicFiles
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultApprovalGate
import com.danielealbano.androidremotecontrolmcp.ui.AgentApprovalActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which apps the assistant may always work in (true) or never (false), by package. An app that
 * is not listed is asked about each time. Kept in one small file.
 */
class AppGrantsFile(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun all(): Map<String, Boolean> {
        val raw = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
        return raw
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<Map<String, Boolean>>(it) }.getOrNull() }
            .orEmpty()
    }

    /** Remembers the answer for [packageName]; null forgets it, so the app is asked about again. */
    @Synchronized
    fun set(
        packageName: String,
        allowed: Boolean?,
    ) {
        val grants = all().toMutableMap()
        if (allowed == null) grants.remove(packageName) else grants[packageName] = allowed
        AtomicFiles.save(file, json.encodeToString(grants.toMap()))
    }
}

/** The user's answer to "may the assistant work in this app?". */
enum class AppAnswer {
    /** Yes, for this task. */
    ONCE,

    /** Yes, and do not ask again for this app. */
    ALWAYS,

    /** No, this time. (Switching an app off for good is done in the settings.) */
    DENY,
}

/** The question on screen. */
data class AppApprovalRequest(
    val id: String,
    val packageName: String,
    val appLabel: String,
)

/** Decides from what is remembered and what the user answers. Pure apart from the two functions it is given. */
class AppGateRules(
    private val remembered: (String) -> Boolean?,
    private val remember: (String) -> Unit,
) {
    private val allowedThisTask = mutableSetOf<String>()

    /** A new task starts: "only this time" answers of the last one no longer count. */
    fun newTask() = allowedThisTask.clear()

    /** Whether the app is settled without asking: true / false, or null when the user must be asked. */
    fun settled(packageName: String): Boolean? = if (packageName in allowedThisTask) true else remembered(packageName)

    /** Applies the user's [answer] and says whether the agent may go on. */
    fun apply(
        packageName: String,
        answer: AppAnswer,
    ): Boolean {
        if (answer == AppAnswer.ALWAYS) remember(packageName)
        if (answer != AppAnswer.DENY) allowedThisTask += packageName
        return answer != AppAnswer.DENY
    }
}

/**
 * Asks the user, on the phone's own screen, before the assistant starts working in an app:
 * only this time, always, or no. While the question is up every tool gesture is refused (the
 * same gate as the vault's approval card), so the agent cannot answer it itself.
 */
@Singleton
class AppApprovals
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val state: AgentState,
    ) : AppGate {
        private val rules = AppGateRules({ state.grants.all()[it] }, { state.grants.set(it, true) })
        private val neutral = NeutralApps(context)
        private val asking = Mutex()
        private val _pending = MutableStateFlow<AppApprovalRequest?>(null)

        /** The question currently shown, or null. */
        val pending: StateFlow<AppApprovalRequest?> = _pending.asStateFlow()

        @Volatile
        private var waiter: Pair<String, CompletableDeferred<AppAnswer>>? = null
        private var counter = 0

        @Volatile
        private var unattended = false

        /**
         * A new task starts. An [unattended] one was asked for from elsewhere (a message channel):
         * nobody is at the phone to answer, and the one asking must not be the one allowing, so
         * only apps the user allowed for good, on the phone, may be worked in.
         */
        fun newTask(unattended: Boolean = false) {
            this.unattended = unattended
            rules.newTask()
        }

        /** Nobody's apps are never asked about: this app, the home screen, the system's own surfaces, keyboards. */
        override fun settled(packageName: String): Boolean? {
            val nobodys = neutral.contains(packageName)
            return if (nobodys) true else rules.settled(packageName)
        }

        override suspend fun ask(packageName: String): Boolean =
            asking.withLock {
                val settled = rules.settled(packageName)
                when {
                    settled != null -> settled
                    unattended -> false
                    else -> rules.apply(packageName, show(packageName))
                }
            }

        private suspend fun show(packageName: String): AppAnswer {
            counter += 1
            val request = AppApprovalRequest("a$counter", packageName, labelOf(packageName))
            val answer = CompletableDeferred<AppAnswer>()
            waiter = request.id to answer
            _pending.value = request
            VaultApprovalGate.pending = true
            return try {
                launchScreen()
                // No answer is a no: the task must not sit there holding the phone.
                withTimeoutOrNull(TIMEOUT_MS) { answer.await() } ?: AppAnswer.DENY
            } finally {
                waiter = null
                _pending.value = null
                VaultApprovalGate.pending = false
            }
        }

        /** The user's answer to the request [requestId]; an answer to an older question is ignored. */
        fun answer(
            requestId: String,
            answer: AppAnswer,
        ) {
            val current = waiter ?: return
            if (current.first == requestId) current.second.complete(answer)
        }

        private fun labelOf(packageName: String): String =
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)

        private fun launchScreen() {
            val intent =
                Intent(context, AgentApprovalActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            runCatching { context.startActivity(intent, options.toBundle()) }.onFailure { e ->
                Log.e(TAG, "cannot show the app approval: ${e.message}")
                waiter?.second?.complete(AppAnswer.DENY)
            }
        }

        private companion object {
            const val TAG = "MCP:AppApprovals"
            const val TIMEOUT_MS = 60_000L
        }
    }

/**
 * Apps that are nobody's to allow: the task passes through them on the way (the home screen,
 * the system's dialogs and permission prompts, a keyboard) and this app itself.
 */
class NeutralApps(
    private val context: Context,
) {
    private val found: Set<String> by lazy {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // Only the home screen in use: other apps declare a home activity too (AOSP Settings has a
        // fallback one) and are not nobody's for that.
        val launchers =
            runCatching {
                listOfNotNull(pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName)
            }
        val keyboards =
            runCatching {
                val manager = context.getSystemService(InputMethodManager::class.java)
                manager.inputMethodList.map { it.packageName }
            }
        SYSTEM + context.packageName + launchers.getOrDefault(emptyList()) + keyboards.getOrDefault(emptyList())
    }

    fun contains(packageName: String): Boolean = packageName in found

    private companion object {
        val SYSTEM =
            setOf(
                "android",
                "com.android.systemui",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.intentresolver",
            )
    }
}

/** The agent's files: the task history and the per-app permissions. */
@Singleton
class AgentState
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val dir = File(context.filesDir, "agent")
        val runs = AgentRuns(AgentHistoryFile(File(dir, "history.json")))
        val grants = AppGrantsFile(File(dir, "app_grants.json"))
    }
