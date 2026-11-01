package com.somecatcode.ebookreader.ui.screens

import com.somecatcode.ebookreader.reader.HostToReader
import com.somecatcode.ebookreader.reader.ReaderToHost
import com.somecatcode.ebookreader.reader.SpeechEngine
import com.somecatcode.ebookreader.reader.SpeechEvent
import com.somecatcode.ebookreader.reader.SpeechVoice
import com.somecatcode.ebookreader.reader.TtsSegment
import com.somecatcode.ebookreader.reader.chooseSpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** Read-aloud controls of the reader. */
data class ReadAloudState(
    /** The control bar is shown. */
    val active: Boolean = false,
    val playing: Boolean = false,
    /** Waiting for the speech engine or the next paragraph. */
    val loading: Boolean = false,
    /** Speed, 1.0 = normal. */
    val rate: Float = 1f,
    /** Language spoken now (BCP 47). */
    val language: String? = null,
    /** Language of the text without an installed voice (reading falls back to [language]). */
    val missingVoice: String? = null,
    /** Voices for [language] and the chosen one (null = default voice). */
    val voices: List<SpeechVoice> = emptyList(),
    val voice: String? = null,
    /** This device has no speech engine at all. */
    val unavailable: Boolean = false,
    /** The end of the book was read. */
    val finished: Boolean = false,
)

/** Stored choices: speed and the voice per language. */
interface ReadAloudPrefs {
    var rate: Float
    fun voice(language: String): String?
    fun setVoice(language: String, voice: String?)
}

/** [ReadAloudPrefs] that forget everything (tests, previews). */
class MemoryReadAloudPrefs(override var rate: Float = 1f) : ReadAloudPrefs {
    private val voices = HashMap<String, String>()
    override fun voice(language: String): String? = voices[language]
    override fun setVoice(language: String, voice: String?) {
        if (voice == null) voices.remove(language) else voices[language] = voice
    }
}

/**
 * Read aloud for one reader: asks the page for paragraphs ([HostToReader.Tts], answered with
 * [ReaderToHost.TtsBlock]), speaks their sentences with [SpeechEngine] in the language of the text
 * (fallback: the app language) and reports every started sentence back so the page highlights it.
 * Survives a new WebView (rotation): [onPageOpened] continues at the visible page.
 *
 * @param appLocale the app's language (in-app setting or system), fallback when the book declares none
 */
class ReadAloud(
    private val scope: CoroutineScope,
    private val engineFactory: () -> SpeechEngine,
    private val prefs: ReadAloudPrefs,
    private val bookLanguage: () -> String?,
    var appLocale: Locale = Locale.getDefault(),
) {
    private val _state = MutableStateFlow(ReadAloudState(rate = prefs.rate))
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<HostToReader>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Messages for the page; the reader screen forwards them to its host. */
    val commands: SharedFlow<HostToReader> = _commands.asSharedFlow()

    private var engine: SpeechEngine? = null
    private var engineReady = false
    /** Paragraph being spoken; engine events of older ones are ignored. */
    private var block = 0
    private var segments: List<TtsSegment> = emptyList()

    /** Starts reading at the visible page (or continues a paused reading). */
    fun start() {
        if (_state.value.active && !_state.value.playing && !_state.value.finished) return resume()
        _state.update { it.copy(active = true, playing = true, loading = true, finished = false, unavailable = false) }
        withEngine { request("start") }
    }

    fun pause() {
        if (!_state.value.playing) return
        block++
        engine?.stop()
        _state.update { it.copy(playing = false, loading = false) }
    }

    fun resume() {
        if (!_state.value.active) return start()
        _state.update { it.copy(playing = true, loading = true, finished = false) }
        withEngine { request("resume") }
    }

    fun togglePlay() = if (_state.value.playing) pause() else if (_state.value.active) resume() else start()

    fun next() = skip("next")

    fun previous() = skip("prev")

    /** Closes the controls; the highlight on the page goes away. */
    fun stop() {
        block++
        engine?.stop()
        segments = emptyList()
        _state.update { it.copy(active = false, playing = false, loading = false, finished = false) }
        _commands.tryEmit(HostToReader.Tts("stop"))
    }

    fun setRate(rate: Float) {
        prefs.rate = rate
        _state.update { it.copy(rate = rate) }
        restartSentence()
    }

    fun setVoice(voice: String?) {
        val language = _state.value.language ?: return
        prefs.setVoice(language, voice)
        _state.update { it.copy(voice = voice) }
        restartSentence()
    }

    /** A (new) page finished opening the book: continue at the visible page while reading. */
    fun onPageOpened() {
        if (_state.value.playing) {
            block++
            engine?.stop()
            _state.update { it.copy(loading = true) }
            request("start")
        }
    }

    fun onEvent(event: ReaderToHost) {
        when (event) {
            is ReaderToHost.TtsBlock -> speak(event)
            ReaderToHost.TtsEnd -> if (_state.value.active) {
                block++
                engine?.stop()
                _state.update { it.copy(playing = false, loading = false, finished = true) }
            }
            else -> Unit
        }
    }

    fun release() {
        block++
        engine?.shutdown()
        engine = null
        engineReady = false
    }

    // ---- internals -----------------------------------------------------------------------------

    private fun skip(action: String) {
        if (!_state.value.active) return
        block++
        engine?.stop()
        _state.update { it.copy(playing = true, loading = true, finished = false) }
        withEngine { request(action) }
    }

    /** New speed or voice: the current paragraph again, from the sentence being spoken. */
    private fun restartSentence() {
        if (!_state.value.playing) return
        block++
        engine?.stop()
        request("resume")
    }

    private fun request(action: String) {
        _commands.tryEmit(HostToReader.Tts(action))
    }

    private fun withEngine(then: () -> Unit) {
        val current = engine
        if (current != null && engineReady) return then()
        val created = current ?: engineFactory().also { engine = it }
        created.init { ok ->
            scope.launch {
                engineReady = ok
                if (!ok) {
                    _state.update { it.copy(active = false, playing = false, loading = false, unavailable = true) }
                } else if (_state.value.playing) {
                    then()
                }
            }
        }
    }

    private fun speak(event: ReaderToHost.TtsBlock) {
        val s = _state.value
        val e = engine
        if (!s.active || !s.playing || e == null || !engineReady) return
        segments = event.segments
        val language = chooseSpeechLanguage(event.lang, bookLanguage(), appLocale, e::isLanguageAvailable)
        val tag = language.locale.toLanguageTag()
        val voices = e.voices().filter { it.locale.language == language.locale.language }
            .sortedWith(compareBy<SpeechVoice>({ it.network }, { -it.quality }, { it.name }))
        val voice = prefs.voice(tag)?.takeIf { name -> voices.any { it.name == name } }
        val id = ++block
        _state.update {
            it.copy(loading = false, language = tag, missingVoice = language.missing?.toLanguageTag(), voices = voices, voice = voice)
        }
        e.speak(id, segments.map { it.text }, language.locale, voice, s.rate) { ev -> scope.launch { onSpeech(ev) } }
    }

    private fun onSpeech(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Started -> if (event.block == block) {
                segments.getOrNull(event.index)?.mark?.let { _commands.tryEmit(HostToReader.TtsMark(it)) }
            }
            is SpeechEvent.BlockDone -> if (event.block == block && _state.value.playing) {
                _state.update { it.copy(loading = true) }
                request("next")
            }
            // a sentence the engine cannot say: go on with the next paragraph
            is SpeechEvent.Failed -> if (event.block == block && _state.value.playing) request("next")
            SpeechEvent.FocusLost -> pause()
        }
    }
}
