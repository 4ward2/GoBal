package com.danielealbano.androidremotecontrolmcp.ui.navigation

sealed class TopLevelRoute(
    val route: String,
) {
    data object Chat : TopLevelRoute("chat")

    data object Settings : TopLevelRoute("settings")

    /** The contact page of the active character (or the assistant): avatar, texts, model, memory files. */
    data object Contact : TopLevelRoute("contact")

    /** Group chats: several agents, some of them admins who hand out and accept the work. */
    data object Group : TopLevelRoute("group")

    /** No longer a tab: opens Settings → 服务状态. Kept so existing `tab=server` intents keep working. */
    data object Server : TopLevelRoute("server")

    /** No longer a tab: opens Settings → 关于. Kept so existing `tab=about` intents keep working. */
    data object About : TopLevelRoute("about")

    /** Not a tab: opens Settings → 技能 (used when a recording is finished from the recording bar). */
    data object Skills : TopLevelRoute("skills")
}

sealed class SettingsRoute(
    val route: String,
) {
    data object Index : SettingsRoute("settings/index")

    data object Chat : SettingsRoute("settings/chat")

    data object Providers : SettingsRoute("settings/providers")

    data object Assist : SettingsRoute("settings/assist")

    /** Recorded / agent-saved flows that replay by name. */
    data object Skills : SettingsRoute("settings/skills")

    /** Every phone task the assistant did, step by step. */
    data object TaskHistory : SettingsRoute("settings/tasks")

    /** The apps the assistant may always, or never, work in. */
    data object AppGrants : SettingsRoute("settings/app_grants")

    data object Vault : SettingsRoute("settings/vault")

    data object Server : SettingsRoute("settings/server")

    data object About : SettingsRoute("settings/about")

    /** Light / dark and text size. */
    data object Appearance : SettingsRoute("settings/appearance")

    /** How much of the app is shown: simple, office or developer. */
    data object Mode : SettingsRoute("settings/mode")

    /** Save the user's content to a file, or put one back. */
    data object Backup : SettingsRoute("settings/backup")

    /** The computers this phone is bound to, and the agents on them. */
    data object Computers : SettingsRoute("settings/computers")

    /** Every task on the phone and on every bound computer, in one list (developer tier). */
    data object Dashboard : SettingsRoute("settings/remote/dashboard")

    data object Weixin : SettingsRoute("settings/weixin")

    /** Setting up the connection to the phone's own wireless debugging (phones without root). */
    data object Shell : SettingsRoute("settings/shell")

    /** Everything the app remembers, to read and correct. */
    data object MemoryLibrary : SettingsRoute("settings/memory")

    data object General : SettingsRoute("settings/general")

    data object Access : SettingsRoute("settings/access")

    data object OAuthClients : SettingsRoute("settings/access/clients")

    data object Security : SettingsRoute("settings/security")

    data object Tunnel : SettingsRoute("settings/tunnel")

    data object McpTools : SettingsRoute("settings/mcp_tools")

    data object Permissions : SettingsRoute("settings/permissions")

    data object Storage : SettingsRoute("settings/storage")

    data object ChannelSettings : SettingsRoute("settings/channel")

    data object NotificationFilter : SettingsRoute("settings/channel/notification_filter")

    data object WifiMonitor : SettingsRoute("settings/channel/wifi_monitor")
}
