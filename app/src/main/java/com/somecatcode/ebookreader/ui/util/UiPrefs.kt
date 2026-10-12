package com.somecatcode.ebookreader.ui.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny UI-only preferences that are not part of `AppSettings` (owned by the data layer):
 * whether the notification permission was already requested, the reader's keep-screen-on choice and
 * whether the reader's gesture help and the introduction at the first start were shown.
 */
class UiPrefs(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean("notification_permission_asked", false)
        set(value) = prefs.edit().putBoolean("notification_permission_asked", value).apply()

    var readerKeepScreenOn: Boolean
        get() = prefs.getBoolean("reader_keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("reader_keep_screen_on", value).apply()

    /** The introduction ([com.somecatcode.ebookreader.ui.screens.GuideScreen]) was shown once. */
    var guideShown: Boolean
        get() = prefs.getBoolean("guide_shown", false)
        set(value) = prefs.edit().putBoolean("guide_shown", value).apply()

    var readerGestureHintShown: Boolean
        get() = prefs.getBoolean("reader_gesture_hint_shown", false)
        set(value) = prefs.edit().putBoolean("reader_gesture_hint_shown", value).apply()
}
