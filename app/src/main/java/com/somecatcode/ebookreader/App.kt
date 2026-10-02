package com.somecatcode.ebookreader

import android.app.Application
import android.os.Build

/**
 * Application class. Owns the [AppContainer] (manual dependency injection, no Hilt).
 * Tests can replace the container before the first activity starts via [App.container].
 */
class App : Application() {

    /** Single composition root of the app. Swappable in tests (`app.container = FakeContainer()`). */
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this).also {
            // Robolectric UI tests replace the container; do not start WorkManager machinery there.
            if (!Build.FINGERPRINT.orEmpty().contains("robolectric")) it.startBackgroundWork()
        }
    }
}
