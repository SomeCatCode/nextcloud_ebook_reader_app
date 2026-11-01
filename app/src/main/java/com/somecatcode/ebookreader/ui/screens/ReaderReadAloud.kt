package com.somecatcode.ebookreader.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.reader.SpeechVoice
import java.text.NumberFormat
import java.util.Locale

/** Callbacks of the read-aloud bar. */
class ReadAloudActions(
    val onStart: () -> Unit = {},
    val onTogglePlay: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onRate: (Float) -> Unit = {},
    val onVoice: (String?) -> Unit = {},
)

private val RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** The app's language (in-app setting or system), observed so a change re-renders. */
@Composable
private fun appLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** Name of a language in the app's language, e.g. "Deutsch" / "German" / "ドイツ語". */
@Composable
private fun languageName(tag: String): String {
    val app = appLocale()
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayLanguage(app).replaceFirstChar { if (it.isLowerCase()) it.titlecase(app) else it.toString() }
        .ifBlank { tag }
}

@Composable
private fun rateText(rate: Float): String {
    val app = appLocale()
    val format = NumberFormat.getNumberInstance(app).apply { minimumFractionDigits = 1; maximumFractionDigits = 2 }
    return stringResource(R.string.read_aloud_rate, format.format(rate))
}

/**
 * Controls while reading aloud: previous / play-pause / next paragraph, speed, voice, close. Shows
 * which language is spoken when the text's language has no voice installed, with a way to install one.
 */
@Composable
fun ReadAloudBar(state: ReadAloudState, actions: ReadAloudActions, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rateMenu by remember { mutableStateOf(false) }
    var voiceDialog by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth().testTag("read_aloud_bar"), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            state.missingVoice?.let { missing ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.read_aloud_missing_voice, languageName(missing), languageName(state.language ?: missing)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    TextButton(onClick = { installVoices(context) }) { Text(stringResource(R.string.read_aloud_install_voice)) }
                }
            }
            if (state.finished) {
                Text(
                    stringResource(R.string.read_aloud_finished),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onPrevious, modifier = Modifier.testTag("read_aloud_prev")) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.read_aloud_previous))
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (state.loading && state.playing) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = actions.onTogglePlay, modifier = Modifier.testTag("read_aloud_play")) {
                            Icon(
                                if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(if (state.playing) R.string.read_aloud_pause else R.string.read_aloud_play),
                            )
                        }
                    }
                }
                IconButton(onClick = actions.onNext, modifier = Modifier.testTag("read_aloud_next")) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.read_aloud_next))
                }
                Box {
                    TextButton(onClick = { rateMenu = true }, modifier = Modifier.testTag("read_aloud_rate")) { Text(rateText(state.rate)) }
                    DropdownMenu(expanded = rateMenu, onDismissRequest = { rateMenu = false }) {
                        RATES.forEach { rate ->
                            DropdownMenuItem(text = { Text(rateText(rate)) }, onClick = { rateMenu = false; actions.onRate(rate) })
                        }
                    }
                }
                Text(
                    state.language?.let { languageName(it) }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                IconButton(onClick = { voiceDialog = true }, enabled = state.language != null, modifier = Modifier.testTag("read_aloud_voice")) {
                    Icon(Icons.Filled.RecordVoiceOver, contentDescription = stringResource(R.string.read_aloud_voice))
                }
                IconButton(onClick = actions.onStop, modifier = Modifier.testTag("read_aloud_stop")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.read_aloud_stop))
                }
            }
        }
    }
    if (voiceDialog) {
        VoiceDialog(state, onPick = { voiceDialog = false; actions.onVoice(it) }, onDismiss = { voiceDialog = false }, onInstall = { installVoices(context) })
    }
}

@Composable
private fun VoiceDialog(state: ReadAloudState, onPick: (String?) -> Unit, onDismiss: () -> Unit, onInstall: () -> Unit) {
    val language = state.language ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.read_aloud_voice_title, languageName(language))) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { VoiceRow(stringResource(R.string.read_aloud_voice_default), state.voice == null) { onPick(null) } }
                items(state.voices, key = { it.name }) { voice ->
                    VoiceRow(voiceLabel(voice, state.voices), state.voice == voice.name) { onPick(voice.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onInstall) { Text(stringResource(R.string.read_aloud_install_voice)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Deutsch (Österreich) 2 · online": voice names of engines are technical, so they are numbered per region. */
@Composable
private fun voiceLabel(voice: SpeechVoice, all: List<SpeechVoice>): String {
    val app = appLocale()
    val region = voice.locale.getDisplayCountry(app).takeIf { it.isNotBlank() }
    val base = listOfNotNull(languageName(voice.locale.toLanguageTag()), region?.let { "($it)" }).joinToString(" ")
    val sameRegion = all.filter { it.locale == voice.locale }
    val number = if (sameRegion.size > 1) " ${sameRegion.indexOf(voice) + 1}" else ""
    val online = if (voice.network) " · " + stringResource(R.string.read_aloud_voice_online) else ""
    return base + number + online
}

/** Opens the engine's voice download (or the system speech settings). */
private fun installVoices(context: Context) {
    val intents = listOf(
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
        Intent("com.android.settings.TTS_SETTINGS"),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // try the next one
        }
    }
}
