package com.junkfood.seal.ui.common

object Route {

    const val HOME = "home"
    const val DOWNLOADS = "download_history"
    const val PLAYLIST = "playlist"
    const val SETTINGS = "settings"
    const val FORMAT_SELECTION = "format"
    const val TASK_LIST = "task_list"
    const val TASK_LOG = "task_log"

    const val SETTINGS_PAGE = "settings_page"

    const val APPEARANCE = "appearance"
    const val GENERAL_DOWNLOAD_PREFERENCES = "general_download_preferences"
    const val ABOUT = "about"
    const val DOWNLOAD_DIRECTORY = "download_directory"
    const val TEMPLATE = "template"
    const val TEMPLATE_EDIT = "template_edit"
    const val DARK_THEME = "dark_theme"
    const val DOWNLOAD_QUEUE = "queue"
    const val DOWNLOAD_FORMAT = "download_format"
    const val NETWORK_PREFERENCES = "network_preferences"
    const val COOKIE_PROFILE = "cookie_profile"
    const val COOKIE_GENERATOR_WEBVIEW = "cookie_webview"
    const val SUBTITLE_PREFERENCES = "subtitle_preferences"
    const val AUTO_UPDATE = "auto_update"
    const val DONATE = "donate"

    const val TASK_HASHCODE = "task_hashcode"
    const val TEMPLATE_ID = "template_id"

    /**
     * Destinations that live inside the [SETTINGS] graph. When one of them is opened from outside
     * that graph (the quick settings sheet on the home page), [SETTINGS_PAGE] has to be pushed
     * underneath it so that going back lands on the settings page instead of the home page.
     */
    val SettingsSubRoutes =
        setOf(
            APPEARANCE,
            GENERAL_DOWNLOAD_PREFERENCES,
            ABOUT,
            DOWNLOAD_DIRECTORY,
            TEMPLATE,
            DARK_THEME,
            DOWNLOAD_FORMAT,
            NETWORK_PREFERENCES,
            COOKIE_PROFILE,
            SUBTITLE_PREFERENCES,
            AUTO_UPDATE,
            DONATE,
        )
}

infix fun String.arg(arg: String) = "$this/{$arg}"

infix fun String.id(id: Int) = "$this/$id"
