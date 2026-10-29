package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * How much of the app is shown. A fresh install starts at [SIMPLE]: one conversation with the
 * assistant, the phone doing what it is asked. [OFFICE] is for people who know their way around a
 * phone a little; [DEVELOPER] shows everything, the agents on computers included.
 */
enum class AppTier(
    val code: String,
) {
    // In order: each shows everything the one before it does.
    SIMPLE("simple"),
    OFFICE("office"),
    DEVELOPER("developer"),
    ;

    /** True when this tier shows at least what [other] shows. */
    fun atLeast(other: AppTier): Boolean = ordinal >= other.ordinal

    companion object {
        val DEFAULT = SIMPLE

        fun of(code: String?): AppTier = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
