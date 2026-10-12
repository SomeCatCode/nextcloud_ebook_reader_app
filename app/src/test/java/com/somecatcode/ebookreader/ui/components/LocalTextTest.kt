package com.somecatcode.ebookreader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTextTest {

    @Test
    fun laggingEchoesDoNotReplaceTheTypedText() {
        val sent = mutableListOf<String>()
        val t = LocalText("") { sent += it }
        t.onChange("a")
        t.onChange("ab")
        t.onChange("abc")
        // the state comes back late, one value at a time
        t.onExternal("a")
        assertEquals("abc", t.text)
        t.onExternal("ab")
        assertEquals("abc", t.text)
        t.onExternal("abc")
        assertEquals("abc", t.text)
        assertEquals(listOf("a", "ab", "abc"), sent)
    }

    @Test
    fun skippedEchoesAndExternalChanges() {
        val t = LocalText("x") {}
        t.onChange("xy")
        t.onChange("xyz")
        t.onExternal("xyz") // conflated: "xy" never arrives
        t.onExternal("") // reset from outside (e.g. search closed)
        assertEquals("", t.text)
        t.onChange("q")
        t.onExternal("xy") // stale value from before the reset is a real change, not an echo
        assertEquals("xy", t.text)
    }
}
