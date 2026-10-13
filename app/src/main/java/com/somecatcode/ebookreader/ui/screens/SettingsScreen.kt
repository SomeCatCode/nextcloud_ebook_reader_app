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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somecatcode.ebookreader.BuildConfig
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.repo.AppSettings
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.reader.ReaderSettings
import com.somecatcode.ebookreader.ui.components.BackButton
import com.somecatcode.ebookreader.ui.components.rememberLocalText
import com.somecatcode.ebookreader.ui.util.containerViewModel
import com.somecatcode.ebookreader.ui.util.readerSettings
import com.somecatcode.ebookreader.ui.util.withReaderSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


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
fun SettingsScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit = {}, onOpenPrivacy: () -> Unit = {}, onOpenGuide: () -> Unit = {}) {
    val vm = containerViewModel { c -> SettingsViewModel(c.settingsRepository) }
    val settings by vm.state.collectAsState()
    SettingsContent(settings, onBack, vm::update, vm::updateReader, onOpenLicenses, onOpenPrivacy = onOpenPrivacy, onOpenGuide = onOpenGuide)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    settings: AppSettings,
    onBack: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateReader: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onOpenLicenses: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    donationUrl: String = DONATION_URL,
    onOpenGuide: () -> Unit = {},
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
            val deviceName = rememberLocalText(settings.deviceName.orEmpty()) { v -> onUpdate { it.copy(deviceName = v.ifBlank { null }) } }
            OutlinedTextField(
                value = deviceName.text,
                onValueChange = deviceName.onChange,
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
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpenGuide).padding(horizontal = 16.dp, vertical = 12.dp).testTag("open_guide")) {
                Text(stringResource(R.string.settings_guide), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_guide_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpenPrivacy).padding(horizontal = 16.dp, vertical = 12.dp).testTag("privacy_policy")) {
                Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.bodyLarge)
            }
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

/** Asset file of the privacy policy for a language: German, Spanish and Japanese have their own text, all others English. */
internal fun privacyAssetFor(language: String): String = when (language.lowercase()) {
    "de", "es", "ja" -> "privacy/${language.lowercase()}.md"
    else -> "privacy/en.md"
}

/** Privacy policy (docs/privacy, bundled as assets/privacy/<language>.md) in the app language. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val text = remember(language) {
        runCatching { context.assets.open(privacyAssetFor(language)).bufferedReader().use { it.readText() } }
            .recoverCatching { context.assets.open(privacyAssetFor("en")).bufferedReader().use { it.readText() } }
            .getOrDefault("")
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_privacy)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag("privacy_text")) {
            for (block in markdownBlocks(text)) {
                val style = when (block.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    3 -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.bodyMedium
                }
                val top = if (block.level > 0) 16.dp else 4.dp
                Text(inlineMarkdown(block.text), style = style, modifier = Modifier.padding(top = top, start = if (block.bullet) 8.dp else 0.dp))
            }
        }
    }
}

internal data class MarkdownBlock(val text: String, val level: Int = 0, val bullet: Boolean = false)

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^\s*[-*]\s+""")
private val LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")

/** Minimal Markdown for the bundled texts: headings (level 1-3), bullet items and paragraphs (joined lines). */
internal fun markdownBlocks(markdown: String): List<MarkdownBlock> {
    val out = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) out += MarkdownBlock(paragraph.toString().trim())
        paragraph.clear()
    }
    for (raw in markdown.lines()) {
        val line = raw.trimEnd()
        val heading = HEADING.find(line)
        when {
            line.isBlank() -> flush()
            heading != null -> {
                flush()
                out += MarkdownBlock(heading.groupValues[2], level = minOf(heading.groupValues[1].length, 3))
            }
            BULLET.containsMatchIn(line) -> {
                flush()
                out += MarkdownBlock("• " + line.replace(BULLET, ""), bullet = true)
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
    }
    flush()
    return out
}

/** **bold** becomes bold; [text](url) becomes "text (url)". */
internal fun inlineMarkdown(text: String): AnnotatedString {
    val linked = text.replace(LINK) { m -> if (m.groupValues[1] == m.groupValues[2]) m.groupValues[1] else "${m.groupValues[1]} (${m.groupValues[2]})" }
    return buildAnnotatedString {
        linked.split("**").forEachIndexed { i, part ->
            if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
        }
    }
}
