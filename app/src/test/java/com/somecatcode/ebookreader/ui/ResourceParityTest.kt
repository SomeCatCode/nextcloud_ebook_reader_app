package com.somecatcode.ebookreader.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Every translated res/values-* file defines exactly the strings and plurals of the English default, with the same placeholders. */
class ResourceParityTest {
    private val resDir: File = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    private class Entries(val strings: Map<String, String>, val plurals: Map<String, Set<String>>)

    private fun load(file: File): Entries {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Set<String>>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val e = children.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = e.textContent
                "plurals" -> plurals[e.getAttribute("name")] = e.getElementsByTagName("item").let { items ->
                    (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
                }
            }
        }
        return Entries(strings, plurals)
    }

    private fun placeholders(text: String) = Regex("""%(\d+\$)?[sd]|%%""").findAll(text).map { it.value.replace(Regex("""^%\d+\$"""), "%") }.sorted().toList()

    @Test
    fun translationsMatchTheDefaultStrings() {
        val translations = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .filter { File(it, "strings.xml").exists() }
        val languages = translations.map { it.name }.toSet()
        for (expected in listOf("values-de", "values-es", "values-ja", "values-fr", "values-ar", "values-tr", "values-fa-rAF")) {
            assertTrue("missing $expected", expected in languages)
        }
        for (file in listOf("strings.xml", "strings_data.xml")) {
            val base = load(File(resDir, "values/$file"))
            for (dir in translations) {
                val other = load(File(dir, file))
                assertEquals("$file names in ${dir.name}", base.strings.keys, other.strings.keys)
                assertEquals("$file plurals in ${dir.name}", base.plurals.keys, other.plurals.keys)
                for ((name, text) in base.strings) {
                    assertEquals("placeholders of $name in ${dir.name}", placeholders(text), placeholders(other.strings.getValue(name)))
                }
                for ((name, quantities) in other.plurals) {
                    assertTrue("$name in ${dir.name} needs 'other'", "other" in quantities)
                }
            }
        }
    }
}
