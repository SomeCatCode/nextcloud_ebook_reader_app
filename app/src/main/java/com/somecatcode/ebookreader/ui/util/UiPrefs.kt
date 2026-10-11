package com.somecatcode.ebookreader.ui.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny UI-only preferences that are not part of `AppSettings` (owned by the data layer):
 * whether the notification permission was already requested, the reader's keep-screen-on choice and
 * whether the reader's gesture help was shown.
 */
class UiPrefs(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean("notification_permission_asked", false)
        set(value) = prefs.edit().putBoolean("notification_permission_asked", value).apply()

    var readerKeepScreenOn: Boolean
        get() = prefs.getBoolean("reader_keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("reader_keep_screen_on", value).apply()

    var readerGestureHintShown: Boolean
        get() = prefs.getBoolean("reader_gesture_hint_shown", false)
        set(value) = prefs.edit().putBoolean("reader_gesture_hint_shown", value).apply()
}
