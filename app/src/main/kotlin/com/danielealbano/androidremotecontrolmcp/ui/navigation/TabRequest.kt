package com.danielealbano.androidremotecontrolmcp.ui.navigation

/** Where an externally requested route lands: the top-level page, plus a Settings sub-page when needed. */
data class TabDestination(
    val tabRoute: String,
    val settingsRoute: String? = null,
)

object TabRequest {
    /** The tab the app opens on. */
    val DEFAULT: TabDestination = TabDestination(TopLevelRoute.Chat.route)

    /**
     * Maps a requested route to a destination. Server and About used to be tabs and now live in
     * Settings; unknown routes (including the removed 灵感 tab, "ideas") fall back to [DEFAULT].
     */
    fun resolve(route: String): TabDestination =
        when (route) {
            TopLevelRoute.Chat.route -> DEFAULT
            TopLevelRoute.Settings.route -> TabDestination(TopLevelRoute.Settings.route)
            TopLevelRoute.Server.route -> TabDestination(TopLevelRoute.Settings.route, SettingsRoute.Server.route)
            TopLevelRoute.About.route -> TabDestination(TopLevelRoute.Settings.route, SettingsRoute.About.route)
            TopLevelRoute.Skills.route -> TabDestination(TopLevelRoute.Settings.route, SettingsRoute.Skills.route)
            SettingsRoute.Shell.route -> TabDestination(TopLevelRoute.Settings.route, SettingsRoute.Shell.route)
            else -> DEFAULT
        }
}
