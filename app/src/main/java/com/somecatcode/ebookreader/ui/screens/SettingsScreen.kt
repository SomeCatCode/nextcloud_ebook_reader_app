package com.somecatcode.ebookreader.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somecatcode.ebookreader.BuildConfig
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.repo.AppSettings
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.reader.ReaderSettings
import com.somecatcode.ebookreader.ui.components.BackButton
import com.somecatcode.ebookreader.ui.util.containerViewModel
import com.somecatcode.ebookreader.ui.util.readerSettings
import com.somecatcode.ebookreader.ui.util.withReaderSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val PRIVACY_POLICY_URL = "https://github.com/SomeCatCode/nextcloud_ebook_reader_app/blob/main/PRIVACY.md"

/** Donation link; empty in the Google Play build (see app/build.gradle.kts), then the entry is hidden. */
val DONATION_URL: String get() = BuildConfig.DONATION_URL

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val state: StateFlow<AppSettings> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun updateReader(transform: (ReaderSettings) -> ReaderSettings) = update { it.withReaderSettings(transform(it.readerSettings())) }
}

/** App settings (theme, e-ink, downloads, device name, reader defaults, about). */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit = {}) {
    val vm = containerViewModel { c -> SettingsViewModel(c.settingsRepository) }
    val settings by vm.state.collectAsState()
    SettingsContent(settings, onBack, vm::update, vm::updateReader, onOpenLicenses)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    settings: AppSettings,
    onBack: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateReader: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onOpenLicenses: () -> Unit = {},
    donationUrl: String = DONATION_URL,
) {
    val reader = settings.readerSettings()
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle(R.string.settings_appearance)
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onUpdate { it.copy(themeMode = mode) } },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier.testTag("theme_$mode"),
                        )
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(R.string.settings_dynamic_color, R.string.settings_dynamic_color_hint, settings.dynamicColor, "dynamic_color") { v -> onUpdate { it.copy(dynamicColor = v) } }
            }
            SwitchRow(R.string.settings_eink, R.string.settings_eink_hint, settings.einkMode, "eink_mode") { v -> onUpdate { it.copy(einkMode = v) } }

            SectionTitle(R.string.settings_downloads)
            SwitchRow(R.string.settings_wifi_only, R.string.settings_wifi_only_hint, settings.downloadOnlyOnWifi, "wifi_only") { v -> onUpdate { it.copy(downloadOnlyOnWifi = v) } }

            SectionTitle(R.string.settings_sync)
            OutlinedTextField(
                value = settings.deviceName.orEmpty(),
                onValueChange = { v -> onUpdate { it.copy(deviceName = v.ifBlank { null }) } },
                label = { Text(stringResource(R.string.settings_device_name)) },
                placeholder = { Text(Build.MODEL) },
                supportingText = { Text(stringResource(R.string.settings_device_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("device_name"),
            )

            SectionTitle(R.string.settings_reader)
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.settings_reader_font_size, reader.fontSize), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = reader.fontSize.toFloat(),
                    onValueChange = { v -> onUpdateReader { it.copy(fontSize = (v / 5f).toInt() * 5) } },
                    valueRange = 60f..200f,
                    modifier = Modifier.testTag("reader_font_size"),
                )
                Text(stringResource(R.string.settings_reader_theme), style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "auto" to R.string.reader_theme_auto, "light" to R.string.theme_light,
                        "sepia" to R.string.reader_theme_sepia, "dark" to R.string.theme_dark,
                    ).forEach { (theme, label) ->
                        FilterChip(
                            selected = reader.theme == theme,
                            onClick = { onUpdateReader { it.copy(theme = theme) } },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier.testTag("reader_theme_$theme"),
                        )
                    }
                }
            }

            SectionTitle(R.string.settings_about)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("version"))
                Text(stringResource(R.string.settings_unofficial), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (donationUrl.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().clickable { uriHandler.openUri(donationUrl) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("donate"),
                ) {
                    Text(stringResource(R.string.settings_donate), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.settings_donate_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LinkRow(R.string.settings_privacy, PRIVACY_POLICY_URL) { uriHandler.openUri(PRIVACY_POLICY_URL) }
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpenLicenses).padding(horizontal = 16.dp, vertical = 12.dp).testTag("third_party_licenses")) {
                Text(stringResource(R.string.settings_third_party_licenses), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: Int, hint: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun LinkRow(title: Int, url: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** Notices of the bundled third-party components (assets/third_party_licenses.txt). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val text = remember {
        runCatching { context.assets.open("third_party_licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_third_party_licenses)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag("licenses_text"),
        )
    }
}
