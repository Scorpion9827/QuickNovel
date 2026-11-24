package com.lagradost.quicknovel.util

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterCleanerTest {
    private fun cleanHtml(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        ChapterCleaner.clean(doc.body())
        return doc.body().html()
    }

    private fun bodyText(html: String): String = Jsoup.parse(cleanHtml(html)).text()

    @Test
    fun normalizesLookAlikeCharacters() {
        assertEquals("freewebnovel.com", ChapterCleaner.normalize("Freewebnᴏvel.cᴏm"))
        assertEquals("freewebnovel", ChapterCleaner.normalize("ｆｒｅｅｗｅｂｎｏｖｅｌ"))
        assertEquals("freewebnovel", ChapterCleaner.normalize("𝐟𝐫𝐞𝐞𝐰𝐞𝐛𝐧𝐨𝐯𝐞𝐥"))
        assertEquals("freewebnovel", ChapterCleaner.normalize("frееwеbnоvеl")) // cyrillic е/о
        assertEquals("freewebnovel", ChapterCleaner.normalize("free​web‍novel"))
        assertEquals("freewebnovel", ChapterCleaner.normalize("ⓕⓡⓔⓔⓦⓔⓑⓝⓞⓥⓔⓛ"))
        assertEquals("freewebnovel.com", ChapterCleaner.normalize("freewebnovel。com"))
    }

    @Test
    fun removesWatermarkSentences() {
        val cases = listOf(
            "The source of this content is Freewebnᴏvel.com.",
            "New novel chapters are published on Freewebnovel.com.",
            "☞ We are moving Freewebnovel.com to Libread.com, Please visit libread.com for more chapters! ☜",
            "Visit ｆｒｅｅｗｅｂｎｏｖｅｌ．ｃｏｍ for the best novel reading experience",
            "Read latest chapters at f r e e w e b n o v e l . c o m",
            "This chapter is updated by 𝐟𝐫𝐞𝐞𝐰𝐞𝐛𝐧𝐨𝐯𝐞𝐥.𝐜𝐨𝐦",
            "Find the original at n0velbin [dot] com",
            "The latest chapters are on some-unknown-site.net",
            "freewebnovel.com",
            "Read more chapters at f r e e w e b n o v e l",
        )
        for (case in cases) {
            assertEquals("Should remove: $case", "", ChapterCleaner.removeWatermarks(case).trim())
        }
    }

    @Test
    fun removesInlineWatermarkButKeepsStory() {
        val text = "He drew his sword. Fr𝐞ewebnovel.com He charged forward without fear."
        val cleaned = ChapterCleaner.removeWatermarks(text)
        assertFalse(ChapterCleaner.normalize(cleaned).contains("freewebnovel"))
        assertTrue(cleaned.contains("He drew his sword."))
        assertTrue(cleaned.contains("charged forward without fear"))
    }

    @Test
    fun keepsNormalProse() {
        val cases = listOf(
            "It was a fast novel, he read it in a day.",
            "The editor of the newspaper frowned at him.",
            "\"Come here,\" she said. Com.",
            "He typed google.com into the browser and waited.",
            "Chapter 12: The Return",
            "Translation of the ancient text took years.",
            "He wrote a web novel in his free time.",
            "She read a free web novel last night.",
        )
        for (case in cases) {
            assertEquals(case, ChapterCleaner.cleanText(case))
        }
    }

    @Test
    fun detectsCreditLines() {
        val credits = listOf(
            "Translator: Atlas Studios Editor: Atlas Studios",
            "Translator: Nyoi_Bo_Studio  Editor: Nyoi_Bo_Studio",
            "TL: Hypersheep Editor: Ryuusei",
            "Translated by: Someone",
            "Translated by Some Group",
            "Edited by: Another",
            "Editor: X",
            "Proofreader: Y",
            "ＴＲＡＮＳＬＡＴＯＲ： X",
            "[Translator: X]",
            "TLC: Z",
        )
        for (c in credits) assertTrue("Credit: $c", ChapterCleaner.isCreditLine(c))
        assertFalse(ChapterCleaner.isCreditLine("The translator spoke quickly."))
        assertFalse(ChapterCleaner.isCreditLine("Ed walked into the room."))
    }

    @Test
    fun removesTrailingCreditFromHeading() {
        assertEquals(
            "Chapter 12 The Return",
            ChapterCleaner.removeCredits("Chapter 12 The Return - Translator: X Editor: Y")
        )
    }

    @Test
    fun cleansHtmlParagraphs() {
        val html = """
            <p>Translator: Atlas Studios Editor: Atlas Studios</p>
            <p>The sun rose over the mountains.</p>
            <p>The source of this content is Freewebnᴏvel.cᴏm.</p>
            <p><span>free</span><span>web</span><span>novel</span>.com</p>
            <p>She smiled.</p>
            <p><img src="a.png"/></p>
            <p></p>
        """.trimIndent()
        val cleaned = cleanHtml(html)
        val text = Jsoup.parse(cleaned).text()
        assertEquals("The sun rose over the mountains. She smiled.", text)
        assertTrue(cleaned.contains("<img"))
        // untouched empty paragraphs are kept
        assertEquals(1, Regex("<p></p>").findAll(cleaned).count())
    }

    @Test
    fun cleansBrSeparatedText() {
        val html = "First line.<br>Translator: X<br>Second line.<br>Visit freewebnovel.com for more chapters!<br>Third line."
        val cleaned = cleanHtml(html)
        assertEquals("First line.<br>Second line.<br>Third line.", cleaned.replace("\n", "").replace("<br />", "<br>"))
    }

    @Test
    fun keepsWatermarkFreeChapterIdentical() {
        val html = "<p>Hello there.</p>\n<p>General Kenobi.</p>"
        assertEquals(Jsoup.parseBodyFragment(html).body().html(), cleanHtml(html))
        assertEquals("Hello there. General Kenobi.", bodyText(html))
    }
}
