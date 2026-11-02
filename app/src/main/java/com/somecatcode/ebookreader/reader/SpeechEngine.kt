package com.somecatcode.ebookreader.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    /** Voice name, language and rate set on the engine. */
    private var applied: Triple<String?, Locale, Float>? = null
    /** Utterances of the block being spoken (index = utterance index of the id). */
    @Volatile private var utterances: List<Utterance> = emptyList()
    /** Sentence last reported as started, as `block:index`. */
    @Volatile private var reported: String? = null
    /** The engine reported a word range at least once: no estimate needed. */
    @Volatile private var rangesSeen = false
    private val main = Handler(Looper.getMainLooper())
    /** Moves the highlight by the estimated speaking time while the engine reports no ranges. */
    private var estimate: Runnable? = null
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
        // setting voice, language or rate makes some engines (Samsung) reload: only on a change
        val chosen: Voice? = voice?.let { name -> runCatching { engine.voices?.firstOrNull { it.name == name } }.getOrNull() }
        val setup = Triple(chosen?.name, locale, rate)
        if (setup != applied) {
            if (chosen != null) engine.voice = chosen else engine.language = locale
            engine.setSpeechRate(rate)
            applied = setup
        }
        audio?.requestAudioFocus(focus)
        if (texts.isEmpty()) {
            events(SpeechEvent.BlockDone(block))
            return
        }
        // a whole paragraph per utterance: one utterance per sentence sounds choppy (a pause and a
        // new start of the voice after every sentence, "Mr." as a sentence of its own)
        val max = runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(MAX_UTTERANCE).coerceIn(500, MAX_UTTERANCE)
        val planned = planUtterances(texts, max)
        cancelEstimate()
        utterances = planned
        planned.forEachIndexed { i, u ->
            val id = "$block:$i:${planned.size}"
            engine.speak(u.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        }
    }

    override fun stop() {
        cancelEstimate()
        tts?.stop()
        audio?.abandonAudioFocusRequest(focus)
    }

    override fun shutdown() {
        stop()
        listener = null
        applied = null
        tts?.shutdown()
        tts = null
    }

    private fun report(block: Int, index: Int) {
        val key = "$block:$index"
        if (key == reported) return
        reported = key
        listener?.invoke(SpeechEvent.Started(block, index))
    }

    private fun cancelEstimate() {
        estimate?.let(main::removeCallbacks)
        estimate = null
    }

    /**
     * Engines without [UtteranceProgressListener.onRangeStart] report only the start of an utterance.
     * Then the highlight follows the estimated time per sentence (characters at the speech rate).
     * Runs on the main thread.
     */
    private fun estimateFrom(block: Int, utterance: Utterance, segment: Int) {
        cancelEstimate()
        val local = segment - utterance.firstIndex
        if (rangesSeen || local + 1 >= utterance.starts.size) return
        val chars = utterance.starts[local + 1] - utterance.starts[local]
        val rate = applied?.third ?: 1f
        val task = Runnable {
            if (!rangesSeen) {
                report(block, segment + 1)
                estimateFrom(block, utterance, segment + 1)
            }
        }
        estimate = task
        main.postDelayed(task, (chars * MS_PER_CHAR / rate).toLong())
    }

    /** Utterance ids are `block:index:count`. */
    private inner class Progress : UtteranceProgressListener() {
        private fun parse(id: String?): Triple<Int, Int, Int>? =
            id?.split(':')?.takeIf { it.size == 3 }?.let { p ->
                val (b, i, n) = p.map { it.toIntOrNull() ?: return null }
                Triple(b, i, n)
            }

        override fun onStart(utteranceId: String?) {
            parse(utteranceId)?.let { (b, i, _) ->
                val u = utterances.getOrNull(i) ?: return
                report(b, u.firstIndex)
                main.post { estimateFrom(b, u, u.firstIndex) }
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            parse(utteranceId)?.let { (b, i, _) ->
                val u = utterances.getOrNull(i) ?: return
                if (!rangesSeen) {
                    rangesSeen = true
                    main.post { cancelEstimate() }
                }
                report(b, u.segmentAt(start))
            }
        }

        override fun onDone(utteranceId: String?) {
            parse(utteranceId)?.let { (b, i, n) ->
                main.post { cancelEstimate() }
                if (i == n - 1) listener?.invoke(SpeechEvent.BlockDone(b))
            }
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

    private companion object {
        /** Upper limit for one utterance; a longer paragraph becomes several utterances. */
        const val MAX_UTTERANCE = 3000
        /** Estimated speaking time per character at rate 1.0 (about 14 characters per second). */
        const val MS_PER_CHAR = 70f
    }
}

/** One utterance: the sentences [firstIndex] … of a block joined with spaces; [starts] = offset of each sentence in [text]. */
class Utterance(val text: String, val firstIndex: Int, val starts: IntArray) {
    /** The sentence (index in the block) at character [offset] of [text]. */
    fun segmentAt(offset: Int): Int {
        var i = starts.size - 1
        while (i > 0 && starts[i] > offset) i--
        return firstIndex + i
    }
}

/**
 * Joins the sentences of a block into as few utterances as possible: at most [max] characters each,
 * split only between sentences. A single sentence longer than [max] is an utterance of its own.
 */
fun planUtterances(texts: List<String>, max: Int): List<Utterance> {
    val out = ArrayList<Utterance>()
    val text = StringBuilder()
    val starts = ArrayList<Int>()
    var first = 0
    fun flush() {
        if (starts.isNotEmpty()) out += Utterance(text.toString(), first, starts.toIntArray())
        text.setLength(0)
        starts.clear()
    }
    texts.forEachIndexed { i, sentence ->
        if (starts.isNotEmpty() && text.length + 1 + sentence.length > max) flush()
        if (starts.isEmpty()) first = i else text.append(' ')
        starts += text.length
        text.append(sentence)
    }
    flush()
    return out
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
