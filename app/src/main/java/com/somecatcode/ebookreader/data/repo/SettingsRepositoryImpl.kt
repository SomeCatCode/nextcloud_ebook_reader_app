package com.somecatcode.ebookreader.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.IOException

/** [AppSettings] in a Preferences DataStore. Absent keys fall back to the [AppSettings] defaults. */
class SettingsRepositoryImpl(private val store: DataStore<Preferences>) : SettingsRepository {

    override val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[SYNC_INTERVAL] = next.syncIntervalHours
            prefs[SYNC_WIFI] = next.syncOnlyOnWifi
            prefs[DOWNLOAD_WIFI] = next.downloadOnlyOnWifi
            prefs[DYNAMIC_COLOR] = next.dynamicColor
            prefs[THEME] = next.themeMode
            prefs[LAYOUT] = next.libraryLayout
            prefs[MERGED] = next.mergedLibrary
            prefs[EINK] = next.einkMode
            prefs[HIDE_FINISHED] = next.hideFinished
            prefs.putOrRemove(LAST_ACCOUNT, next.lastAccountId)
            prefs.putOrRemove(READER_SETTINGS, next.readerSettingsJson)
            prefs.putOrRemove(DEVICE_NAME, next.deviceName)
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.putOrRemove(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            syncIntervalHours = this[SYNC_INTERVAL] ?: d.syncIntervalHours,
            syncOnlyOnWifi = this[SYNC_WIFI] ?: d.syncOnlyOnWifi,
            downloadOnlyOnWifi = this[DOWNLOAD_WIFI] ?: d.downloadOnlyOnWifi,
            dynamicColor = this[DYNAMIC_COLOR] ?: d.dynamicColor,
            themeMode = this[THEME] ?: d.themeMode,
            libraryLayout = this[LAYOUT] ?: d.libraryLayout,
            mergedLibrary = this[MERGED] ?: d.mergedLibrary,
            lastAccountId = this[LAST_ACCOUNT],
            einkMode = this[EINK] ?: d.einkMode,
            readerSettingsJson = this[READER_SETTINGS],
            deviceName = this[DEVICE_NAME],
            hideFinished = this[HIDE_FINISHED] ?: d.hideFinished,
        )
    }

    private companion object {
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_hours")
        val SYNC_WIFI = booleanPreferencesKey("sync_only_wifi")
        val DOWNLOAD_WIFI = booleanPreferencesKey("download_only_wifi")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme_mode")
        val LAYOUT = stringPreferencesKey("library_layout")
        val MERGED = booleanPreferencesKey("merged_library")
        val LAST_ACCOUNT = stringPreferencesKey("last_account_id")
        val EINK = booleanPreferencesKey("eink_mode")
        val READER_SETTINGS = stringPreferencesKey("reader_settings_json")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val HIDE_FINISHED = booleanPreferencesKey("hide_finished")
    }
}

/** Current settings once. */
suspend fun SettingsRepository.settingsOnce(): AppSettings = settings.first()
