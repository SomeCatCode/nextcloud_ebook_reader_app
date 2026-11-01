package com.somecatcode.ebookreader.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** A voice of the speech engine. [network] voices need a connection. */
data class SpeechVoice(val name: String, val locale: Locale, val network: Boolean, val quality: Int)

/** What happens while speaking; [block] is the number passed to [SpeechEngine.speak]. */
sealed interface SpeechEvent {
    data class Started(val block: Int, val index: Int) : SpeechEvent
    data class BlockDone(val block: Int) : SpeechEvent
    data class Failed(val block: Int) : SpeechEvent
    /** Another app took the audio (call, navigation, music): reading pauses. */
    data object FocusLost : SpeechEvent
}

/** Text to speech for reading aloud; the Android implementation is [AndroidSpeechEngine]. */
interface SpeechEngine {
    /** Calls [onReady] once the engine is initialised (`false` = no speech engine on this device). */
    fun init(onReady: (Boolean) -> Unit)

    fun isLanguageAvailable(locale: Locale): Boolean
    fun voices(): List<SpeechVoice>

    /**
     * Speaks [texts] in order (queue replaced). [voice] = a name from [voices] or null for the default
     * voice of [locale]. [rate] 1.0 = normal speed.
     */
    fun speak(block: Int, texts: List<String>, locale: Locale, voice: String?, rate: Float, events: (SpeechEvent) -> Unit)

    fun stop()
    fun shutdown()
}

/**
 * [TextToSpeech] with audio focus: speaking takes the focus (other audio ducks or pauses), losing it
 * for longer than a moment (phone call) reports [SpeechEvent.FocusLost].
 */
class AndroidSpeechEngine(private val context: Context) : SpeechEngine {

    private var tts: TextToSpeech? = null
    private var listener: ((SpeechEvent) -> Unit)? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                listener?.invoke(SpeechEvent.FocusLost)
            }
        }
        .build()

    override fun init(onReady: (Boolean) -> Unit) {
        if (tts != null) {
            onReady(true)
            return
        }
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                engine?.setAudioAttributes(attributes)
                engine?.setOnUtteranceProgressListener(Progress())
            }
            onReady(ok)
        }
        tts = engine
    }

    override fun isLanguageAvailable(locale: Locale): Boolean =
        (tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE

    override fun voices(): List<SpeechVoice> = runCatching {
        tts?.voices.orEmpty()
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .map { SpeechVoice(it.name, it.locale, it.isNetworkConnectionRequired, it.quality) }
    }.getOrDefault(emptyList())

    override fun speak(block: Int, texts: List<String>, locale: Locale, voice: String?, rate: Float, events: (SpeechEvent) -> Unit) {
        val engine = tts ?: return events(SpeechEvent.Failed(block))
        listener = events
        val chosen: Voice? = voice?.let { name -> runCatching { engine.voices?.firstOrNull { it.name == name } }.getOrNull() }
        if (chosen != null) engine.voice = chosen else engine.language = locale
        engine.setSpeechRate(rate)
        audio?.requestAudioFocus(focus)
        if (texts.isEmpty()) {
            events(SpeechEvent.BlockDone(block))
            return
        }
        texts.forEachIndexed { i, text ->
            val id = "$block:$i:${texts.size}"
            engine.speak(text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        }
    }

    override fun stop() {
        tts?.stop()
        audio?.abandonAudioFocusRequest(focus)
    }

    override fun shutdown() {
        stop()
        listener = null
        tts?.shutdown()
        tts = null
    }

    /** Utterance ids are `block:index:count`. */
    private inner class Progress : UtteranceProgressListener() {
        private fun parse(id: String?): Triple<Int, Int, Int>? =
            id?.split(':')?.takeIf { it.size == 3 }?.let { p ->
                val (b, i, n) = p.map { it.toIntOrNull() ?: return null }
                Triple(b, i, n)
            }

        override fun onStart(utteranceId: String?) {
            parse(utteranceId)?.let { (b, i, _) -> listener?.invoke(SpeechEvent.Started(b, i)) }
        }

        override fun onDone(utteranceId: String?) {
            parse(utteranceId)?.let { (b, i, n) -> if (i == n - 1) listener?.invoke(SpeechEvent.BlockDone(b)) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            parse(utteranceId)?.let { (b, _, _) -> listener?.invoke(SpeechEvent.Failed(b)) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            // stop() reports interrupted utterances as errors on some engines: not a failure
            if (errorCode == TextToSpeech.ERROR_SERVICE || errorCode == TextToSpeech.ERROR_SYNTHESIS || errorCode == TextToSpeech.ERROR_OUTPUT) {
                parse(utteranceId)?.let { (b, _, _) -> listener?.invoke(SpeechEvent.Failed(b)) }
            }
        }
    }
}

/** The language to read in: [locale] is used, [missing] = the text's language when no voice for it is installed. */
data class SpeechLanguage(val locale: Locale, val missing: Locale?)

/**
 * Picks the speech language: the language of the text (paragraph, else the book), then the app
 * language, then the device language. A text language without an installed voice is reported as
 * [SpeechLanguage.missing] so the reader can offer to install it; reading continues in the fallback.
 */
fun chooseSpeechLanguage(textLang: String?, bookLang: String?, appLocale: Locale, available: (Locale) -> Boolean): SpeechLanguage {
    val wanted = listOfNotNull(textLang, bookLang).map { Locale.forLanguageTag(it.replace('_', '-')) }
        .firstOrNull { it.language.isNotEmpty() && it.language != "und" }
    val candidates = listOfNotNull(wanted, wanted?.let { Locale.forLanguageTag(it.language) }, appLocale, Locale.forLanguageTag(appLocale.language), Locale.getDefault())
    val chosen = candidates.firstOrNull(available) ?: wanted ?: appLocale
    val missing = wanted?.takeIf { it.language != chosen.language }
    return SpeechLanguage(chosen, missing)
}
