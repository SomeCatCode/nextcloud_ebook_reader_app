package com.somecatcode.ebookreader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyMarkdownTest {
    @Test
    fun assetPerLanguageWithEnglishFallback() {
        assertEquals("privacy/de.md", privacyAssetFor("de"))
        assertEquals("privacy/es.md", privacyAssetFor("ES"))
        assertEquals("privacy/ja.md", privacyAssetFor("ja"))
        assertEquals("privacy/en.md", privacyAssetFor("fr"))
    }

    @Test
    fun blocksSplitHeadingsBulletsAndParagraphs() {
        val md = "# Title\n\n## 1. Part\n\nLine one\nline two\n\n- **Item:** text\n* other\n"
        assertEquals(
            listOf(
                MarkdownBlock("Title", level = 1),
                MarkdownBlock("1. Part", level = 2),
                MarkdownBlock("Line one line two"),
                MarkdownBlock("• **Item:** text", bullet = true),
                MarkdownBlock("• other", bullet = true),
            ),
            markdownBlocks(md),
        )
    }

    @Test
    fun inlineBoldAndLinks() {
        val s = inlineMarkdown("a **b** [site](https://x.y) [https://z](https://z)")
        assertEquals("a b site (https://x.y) https://z", s.text)
        assertEquals(1, s.spanStyles.size)
        assertEquals(2, s.spanStyles[0].start)
        assertEquals(3, s.spanStyles[0].end)
    }
}
