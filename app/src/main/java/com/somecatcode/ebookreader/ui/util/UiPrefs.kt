package com.somecatcode.ebookreader.ui.util

import com.somecatcode.ebookreader.ui.screens.ReadAloudPrefs
import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny UI-only preferences that are not part of `AppSettings` (owned by the data layer):
 * whether the notification permission was already requested, the reader's keep-screen-on choice and
 * whether the reader's gesture help and the introduction at the first start were shown, and the
 * read-aloud speed and voices.
 */
class UiPrefs(context: Context) : ReadAloudPrefs {
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

    override var rate: Float
        get() = prefs.getFloat("tts_rate", 1f)
        set(value) = prefs.edit().putFloat("tts_rate", value).apply()

    override fun voice(language: String): String? = prefs.getString("tts_voice_$language", null)

    override fun setVoice(language: String, voice: String?) =
        prefs.edit().apply { if (voice == null) remove("tts_voice_$language") else putString("tts_voice_$language", voice) }.apply()

    var readerGestureHintShown: Boolean
        get() = prefs.getBoolean("reader_gesture_hint_shown", false)
        set(value) = prefs.edit().putBoolean("reader_gesture_hint_shown", value).apply()
}
