package com.somecatcode.ebookreader.ui.util

import com.somecatcode.ebookreader.data.repo.AppSettings
import com.somecatcode.ebookreader.reader.BridgeJson
import com.somecatcode.ebookreader.reader.ReaderSettings

/** Reader defaults are stored as `ReaderSettings` JSON in `AppSettings.readerSettingsJson`. */
fun AppSettings.readerSettings(): ReaderSettings {
    val stored = readerSettingsJson?.let { json ->
        runCatching { BridgeJson.decodeFromString(ReaderSettings.serializer(), json) }.getOrNull()
    } ?: ReaderSettings()
    return stored.copy(einkMode = einkMode)
}

fun ReaderSettings.toJson(): String = BridgeJson.encodeToString(ReaderSettings.serializer(), this)

fun AppSettings.withReaderSettings(settings: ReaderSettings): AppSettings = copy(readerSettingsJson = settings.toJson())
