package com.somecatcode.ebookreader.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechEngineTest {

    @Test
    fun `a paragraph becomes one utterance with the offset of every sentence`() {
        val u = planUtterances(listOf("Mr.", "Holmes sat down.", "He smiled."), 3000)
        assertEquals(1, u.size)
        assertEquals("Mr. Holmes sat down. He smiled.", u[0].text)
        assertEquals(0, u[0].firstIndex)
        assertArrayEquals(intArrayOf(0, 4, 21), u[0].starts)
    }

    @Test
    fun `the sentence at an offset follows the word ranges of the engine`() {
        val u = planUtterances(listOf("Mr.", "Holmes sat down.", "He smiled."), 3000)[0]
        assertEquals(0, u.segmentAt(0))
        assertEquals(0, u.segmentAt(3))
        assertEquals(1, u.segmentAt(4))
        assertEquals(1, u.segmentAt(11))
        assertEquals(2, u.segmentAt(21))
        assertEquals(2, u.segmentAt(500))
    }

    @Test
    fun `long paragraphs are split between sentences only`() {
        val u = planUtterances(listOf("aaaa", "bbbb", "cccc", "dddddddddddd", "ee"), 10)
        assertEquals(listOf("aaaa bbbb", "cccc", "dddddddddddd", "ee"), u.map { it.text })
        assertEquals(listOf(0, 2, 3, 4), u.map { it.firstIndex })
        assertEquals(3, u[2].segmentAt(5))
    }

    @Test
    fun `no sentences give no utterance`() {
        assertEquals(emptyList<Utterance>(), planUtterances(emptyList(), 100))
    }
}
