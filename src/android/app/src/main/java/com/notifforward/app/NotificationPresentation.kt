package com.notifforward.app

/** Keep the original source in the title: some Android skins hide notification subtext. */
internal object NotificationPresentation {
    fun source(appName: String, appId: String): String =
        appName.trim().ifBlank { appId.trim().ifBlank { "未知应用" } }

    fun title(appName: String, appId: String, title: String): String =
        "${source(appName, appId)} · ${title.trim().ifBlank { "新通知" }}"
}
