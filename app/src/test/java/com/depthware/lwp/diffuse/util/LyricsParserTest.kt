package com.depthware.lwp.diffuse.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {

    @Test
    fun parsesSyncedLrcWithOffsetMultiTimestampsAndEnhancedWords() {
        val raw = """
            [ar:Artist]
            [offset:500]
            [00:10.00][00:25.50]Line one
            [00:15.250]v2: <00:15.25>Line <00:15.80>two [bg:Echo]
        """.trimIndent()

        val result = LyricsParser.parseLrc(raw)
        assertEquals(3, result.lyrics.size)
        assertEquals(9_500L, result.lyrics[0].timestamp)
        assertEquals("Line one", result.lyrics[0].text)
        assertEquals(14_750L, result.lyrics[1].timestamp)
        assertEquals("v2", result.lyrics[1].agent)
        assertEquals("Line two", result.lyrics[1].text)
        assertEquals("Echo", result.lyrics[1].background)
        assertEquals(false, result.lyrics[1].isMain)
        assertEquals(2, result.lyrics[1].words.size)
        assertEquals(14_750L, result.lyrics[1].words[0].startTimeMs)
        assertEquals(15_300L, result.lyrics[1].words[0].endTimeMs)
        assertEquals("Line", result.lyrics[1].text.substring(result.lyrics[1].words[0].startOffset, result.lyrics[1].words[0].endOffset))
        assertEquals("two", result.lyrics[1].text.substring(result.lyrics[1].words[1].startOffset, result.lyrics[1].words[1].endOffset))
        assertEquals(25_000L, result.lyrics[2].timestamp)
        assertTrue(result.lyrics[0].isMain)
    }

    @Test
    fun parsesAppleMusicTtmlWordToWordAndBackgroundVocals() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word">
              <head><metadata><ttm:agent type="person" xml:id="v1"><ttm:name type="full">Vocal 1</ttm:name></ttm:agent></metadata></head>
              <body>
                <div>
                  <p begin="27.395" end="28.960" ttm:agent="v1"><span begin="27.395" end="27.549">I</span> <span begin="27.549" end="27.740">been</span> <span begin="27.740" end="28.077">e</span><span begin="28.077" end="28.960">nough</span></p>
                  <p begin="2:24.800" end="2:26.452" ttm:agent="v1"><span ttm:role="x-bg" begin="2:24.800" end="2:26.452"><span begin="2:24.800" end="2:25.148">(Hey</span> <span begin="2:25.148" end="2:26.452">now)</span></span> <span begin="2:25.620" end="2:26.452">Call</span></p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val result = LyricsParser.parseTtml(ttml)
        assertEquals(2, result.lyrics.size)
        val first = result.lyrics[0]
        assertEquals(27_395L, first.timestamp)
        assertEquals("I been enough", first.text)
        assertEquals(4, first.words.size)
        assertEquals("e", first.text.substring(first.words[2].startOffset, first.words[2].endOffset))
        assertEquals("nough", first.text.substring(first.words[3].startOffset, first.words[3].endOffset))
        val second = result.lyrics[1]
        assertEquals("Hey now", second.background)
        assertEquals(2, second.backgroundWords.size)
        assertEquals(144_800L, second.backgroundTimestamp)
        assertEquals("Call", second.text)
        assertEquals(145_620L, second.timestamp)
    }

    @Test
    fun parsesPlainLyricsFallback() {
        val raw = "First line\n\nSecond line"
        val result = LyricsParser.parse(raw)
        assertEquals(2, result.lyrics.size)
        assertEquals(0L, result.lyrics[0].timestamp)
        assertEquals(4_000L, result.lyrics[1].timestamp)
        assertEquals("Second line", result.lyrics[1].text)
    }
}
