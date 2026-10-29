package com.danielealbano.androidremotecontrolmcp.services.vault

import kotlinx.serialization.Serializable

/**
 * Personal vault ("私人保险箱"): the user's own details (phone, address, passwords, …) kept
 * encrypted on the device. The model only ever sees slot ids and labels; values are written
 * into input fields by code (`vault_fill`) and scrubbed from everything read back.
 */
@Serializable
enum class VaultCategory(
    val label: String,
) {
    IDENTITY("身份"),
    CONTACT("联系方式"),
    ADDRESS("地址"),
    CREDENTIAL("密码 / 令牌"),
    PREFERENCE("偏好"),
    OTHER("其他"),
}

/**
 * One vault slot. [value] is the secret itself; it is never serialized into tool output.
 *
 * @property allowedPackages when non-empty, the slot may only be filled into these apps.
 * @property sensitive every fill asks the user on the phone; otherwise only the first fill per app.
 */
@Serializable
data class VaultEntry(
    val id: String,
    val label: String,
    val category: VaultCategory,
    val value: String,
    val sensitive: Boolean,
    val allowedPackages: List<String> = emptyList(),
    val updatedAt: Long = 0L,
) {
    override fun toString(): String =
        "VaultEntry(id=$id, label=$label, category=$category, sensitive=$sensitive, " +
            "allowedPackages=$allowedPackages, value=<redacted>)"

    fun toSlot(): VaultSlot =
        VaultSlot(
            id = id,
            label = label,
            category = category,
            sensitive = sensitive,
            allowedPackages = allowedPackages,
            hasValue = value.isNotEmpty() || category == VaultCategory.CREDENTIAL,
        )
}

/** What the model and the UI list may see about a slot: everything except the value. */
@Serializable
data class VaultSlot(
    val id: String,
    val label: String,
    val category: VaultCategory,
    val sensitive: Boolean,
    val allowedPackages: List<String>,
    val hasValue: Boolean,
)

@Serializable
data class VaultFillRecord(
    val slotId: String,
    val packageName: String,
    val timestamp: Long,
    val outcome: String,
)

object VaultValidation {
    private val ID_REGEX = Regex("[a-z][a-z0-9_]{0,31}")
    private val PACKAGE_REGEX = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    const val MAX_LABEL = 24
    const val MAX_VALUE = 512

    /** Returns an error message, or null when [entry] is valid. */
    fun validate(entry: VaultEntry): String? =
        when {
            !ID_REGEX.matches(entry.id) -> "槽位名只能用小写字母、数字、下划线，且以字母开头（最长 32）"
            entry.label.isBlank() -> "名称不能为空"
            entry.label.length > MAX_LABEL -> "名称最多 $MAX_LABEL 个字"
            entry.value.isEmpty() -> "内容不能为空"
            entry.value.length > MAX_VALUE -> "内容最多 $MAX_VALUE 个字符"
            entry.allowedPackages.any { !PACKAGE_REGEX.matches(it) } -> "限定应用里有无效的包名"
            else -> null
        }
}
