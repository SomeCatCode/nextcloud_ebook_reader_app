package com.somecatcode.ebookreader.ui.screens

import com.somecatcode.ebookreader.reader.HostToReader
import com.somecatcode.ebookreader.reader.ReaderToHost
import com.somecatcode.ebookreader.reader.SpeechEngine
import com.somecatcode.ebookreader.reader.SpeechEvent
import com.somecatcode.ebookreader.reader.SpeechVoice
import com.somecatcode.ebookreader.reader.TtsSegment
import com.somecatcode.ebookreader.reader.chooseSpeechLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ReadAloudTest {

    /** Speech engine with German and English voices (unless told otherwise). */
    private class FakeEngine(var ok: Boolean = true, val languages: Set<String> = setOf("de", "en")) : SpeechEngine {
        data class Spoken(val block: Int, val texts: List<String>, val locale: Locale, val voice: String?, val rate: Float)

        val spoken = mutableListOf<Spoken>()
        var stops = 0
        var shutdown = false
        private var events: ((SpeechEvent) -> Unit)? = null

        override fun init(onReady: (Boolean) -> Unit) = onReady(ok)
        override fun isLanguageAvailable(locale: Locale) = locale.language in languages
        override fun voices() = listOf(
            SpeechVoice("de-local-1", Locale.GERMANY, network = false, quality = 400),
            SpeechVoice("de-net-1", Locale.GERMANY, network = true, quality = 500),
            SpeechVoice("en-local-1", Locale.US, network = false, quality = 400),
        )
        override fun speak(block: Int, texts: List<String>, locale: Locale, voice: String?, rate: Float, events: (SpeechEvent) -> Unit) {
            spoken += Spoken(block, texts, locale, voice, rate)
            this.events = events
        }
        override fun stop() { stops++ }
        override fun shutdown() { shutdown = true }

        fun emit(event: SpeechEvent) = events!!.invoke(event)
        val lastBlock get() = spoken.last().block
    }

    private val german = ReaderToHost.TtsBlock("de-DE", listOf(TtsSegment("0", "Es war einmal."), TtsSegment("1", "Ende.")))

    @Test
    fun speaksParagraphsInTheTextLanguageHighlightsSentencesAndGoesOn() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeEngine()
        val prefs = MemoryReadAloudPrefs(rate = 1.25f)
        val r = ReadAloud(this, { engine }, prefs, { "en" }, appLocale = Locale.ENGLISH)
        val sent = mutableListOf<HostToReader>()
        val job = launch { r.commands.collect { sent += it } }

        r.start()
        assertEquals(listOf<HostToReader>(HostToReader.Tts("start")), sent)
        assertTrue(r.state.value.playing)

        r.onEvent(german)
        val spoken = engine.spoken.single()
        assertEquals(listOf("Es war einmal.", "Ende."), spoken.texts)
        assertEquals("de", spoken.locale.language)
        assertEquals(1.25f, spoken.rate)
        assertEquals("de-DE", r.state.value.language)
        assertNull(r.state.value.missingVoice)
        // local voices first, best quality first
        assertEquals(listOf("de-local-1", "de-net-1"), r.state.value.voices.map { it.name })

        engine.emit(SpeechEvent.Started(engine.lastBlock, 1))
        engine.emit(SpeechEvent.BlockDone(engine.lastBlock))
        assertEquals(listOf(HostToReader.Tts("start"), HostToReader.TtsMark("1"), HostToReader.Tts("next")), sent)

        r.onEvent(ReaderToHost.TtsEnd)
        assertTrue(r.state.value.finished)
        assertFalse(r.state.value.playing)
        job.cancel()
    }

    @Test
    fun pauseIgnoresTheRestAndResumeContinuesAtTheSentence() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeEngine()
        val r = ReadAloud(this, { engine }, MemoryReadAloudPrefs(), { null })
        val sent = mutableListOf<HostToReader>()
        val job = launch { r.commands.collect { sent += it } }
        r.start()
        r.onEvent(german)
        val old = engine.lastBlock
        r.togglePlay() // pause
        assertFalse(r.state.value.playing)
        engine.emit(SpeechEvent.BlockDone(old)) // a late event of the stopped paragraph
        assertEquals(listOf<HostToReader>(HostToReader.Tts("start")), sent)
        r.togglePlay() // resume
        assertEquals(HostToReader.Tts("resume"), sent.last())

        r.next()
        assertEquals(HostToReader.Tts("next"), sent.last())
        r.stop()
        assertEquals(HostToReader.Tts("stop"), sent.last())
        assertFalse(r.state.value.active)
        r.release()
        assertTrue(engine.shutdown)
        job.cancel()
    }

    @Test
    fun aLanguageWithoutVoiceFallsBackToTheAppLanguageAndSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeEngine(languages = setOf("en", "fr"))
        val r = ReadAloud(this, { engine }, MemoryReadAloudPrefs(), { null }, appLocale = Locale.FRENCH)
        r.start()
        r.onEvent(german)
        assertEquals("fr", engine.spoken.single().locale.language)
        assertEquals("de-DE", r.state.value.missingVoice)
    }

    @Test
    fun theChosenVoiceIsKeptPerLanguageAndRestartsTheSentence() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeEngine()
        val prefs = MemoryReadAloudPrefs()
        val r = ReadAloud(this, { engine }, prefs, { null })
        val sent = mutableListOf<HostToReader>()
        val job = launch { r.commands.collect { sent += it } }
        r.start()
        r.onEvent(german)
        r.setVoice("de-net-1")
        assertEquals("de-net-1", prefs.voice("de-DE"))
        assertEquals(HostToReader.Tts("resume"), sent.last())
        r.onEvent(german)
        assertEquals("de-net-1", engine.spoken.last().voice)
        job.cancel()
    }

    @Test
    fun noSpeechEngineEndsReadingAloud() = runTest(UnconfinedTestDispatcher()) {
        val r = ReadAloud(this, { FakeEngine(ok = false) }, MemoryReadAloudPrefs(), { null })
        r.start()
        assertTrue(r.state.value.unavailable)
        assertFalse(r.state.value.active)
    }

    @Test
    fun languageChoice() {
        val have = setOf("de", "en", "ja")
        val available = { l: Locale -> l.language in have }
        assertEquals(Locale.forLanguageTag("de-AT"), chooseSpeechLanguage("de-AT", "en", Locale.ENGLISH, { it.toLanguageTag() == "de-AT" }).locale)
        assertEquals("ja", chooseSpeechLanguage(null, "ja", Locale.ENGLISH, available).locale.language)
        // no language anywhere in the book: the app language
        assertEquals("de", chooseSpeechLanguage(null, null, Locale.GERMAN, available).locale.language)
        val fallback = chooseSpeechLanguage("tr", null, Locale.GERMAN, available)
        assertEquals("de", fallback.locale.language)
        assertEquals("tr", fallback.missing?.language)
        assertNull(chooseSpeechLanguage("und", null, Locale.ENGLISH, available).missing)
    }
}
