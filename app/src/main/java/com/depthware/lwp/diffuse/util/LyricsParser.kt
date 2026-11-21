package com.depthware.lwp.diffuse.util

import com.depthware.lwp.diffuse.ui.lyrics.Lyrics
import com.depthware.lwp.diffuse.ui.lyrics.LyricsLine
import com.depthware.lwp.diffuse.ui.lyrics.LyricsWord
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

object LyricsParser {

    private val timeTagRegex = Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val inlineWordTagRegex = Regex("""<(\d+):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val offsetTagRegex = Regex("""^\[offset:([+-]?\d+)]$""", RegexOption.IGNORE_CASE)
    private val metadataTagRegex = Regex("""^\[[a-zA-Z#]+:[^]]*]$""")
    private val voicePrefixRegex = Regex("""^(v1|v2|1|2|M|F|D)\s*:\s*(.*)$""")
    private val bgBracketRegex = Regex("""\[bg:\s*(.*?)]""", RegexOption.IGNORE_CASE)

    fun parse(raw: String?): Lyrics {
        if (raw.isNullOrBlank()) return Lyrics.Empty
        val trimmed = raw.trim()
        if (trimmed.startsWith("<") && trimmed.contains("<tt")) {
            val ttml = parseTtml(trimmed)
            if (ttml.lyrics.isNotEmpty()) return ttml
        }
        val lrc = parseLrc(trimmed)
        if (lrc.lyrics.isNotEmpty()) return lrc
        return parsePlain(trimmed)
    }

    fun parseLrc(raw: String): Lyrics {
        val lines = raw.lines()
        val parsed = mutableListOf<LyricsLine>()
        var offsetMs = 0L

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val offsetMatch = offsetTagRegex.matchEntire(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            val timeMatches = timeTagRegex.findAll(line).toList()
            if (timeMatches.isEmpty()) {
                if (metadataTagRegex.matches(line)) continue
                continue
            }

            val contentStart = timeMatches.last().range.last + 1
            var rawBody = line.substring(contentStart).trim()

            var background: String? = null
            val bgMatch = bgBracketRegex.find(rawBody)
            if (bgMatch != null) {
                background = bgMatch.groupValues[1].trim().ifEmpty { null }
                rawBody = bgBracketRegex.replace(rawBody, "").trim()
            }

            var agent: String? = null
            val voiceMatch = voicePrefixRegex.matchEntire(rawBody)
            if (voiceMatch != null) {
                agent = voiceMatch.groupValues[1]
                rawBody = voiceMatch.groupValues[2].trim()
            }

            val (cleanText, rawWords) = extractEnhancedLrcWords(rawBody, offsetMs)
            val body = cleanText.trim()
            if (body.isEmpty() && background.isNullOrEmpty()) continue

            for (match in timeMatches) {
                val ts = (parseTimeGroups(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3]
                ) - offsetMs).coerceAtLeast(0L)
                val lineWords = if (rawWords.isNotEmpty()) {
                    rawWords.mapIndexed { idx, w ->
                        val nextStart = rawWords.getOrNull(idx + 1)?.first
                        val resolvedEnd = w.second.takeIf { it > w.first }
                            ?: nextStart?.takeIf { it > w.first }
                            ?: (w.first + 450L)
                        LyricsWord(
                            startTimeMs = w.first,
                            endTimeMs = resolvedEnd,
                            startOffset = w.third.first.coerceIn(0, body.length),
                            endOffset = w.third.second.coerceIn(0, body.length)
                        )
                    }.filter { it.endOffset > it.startOffset }
                } else {
                    emptyList()
                }
                val endTs = lineWords.maxOfOrNull { it.endTimeMs } ?: ts
                parsed.add(
                    LyricsLine(
                        timestamp = ts,
                        agent = agent,
                        text = body.ifEmpty { background.orEmpty() },
                        background = background,
                        endTimestamp = endTs,
                        words = lineWords
                    )
                )
            }
        }

        if (parsed.isEmpty()) return Lyrics.Empty
        return Lyrics(parsed.sortedBy { it.timestamp })
    }

    private fun extractEnhancedLrcWords(
        rawBody: String,
        offsetMs: Long
    ): Pair<String, List<Triple<Long, Long, Pair<Int, Int>>>> {
        val matches = inlineWordTagRegex.findAll(rawBody).toList()
        if (matches.isEmpty()) {
            return rawBody to emptyList()
        }

        val textBuilder = StringBuilder()
        val rawWords = mutableListOf<Triple<Long, Long, Pair<Int, Int>>>()
        var cursor = 0

        if (matches.first().range.first > 0) {
            textBuilder.append(rawBody.substring(0, matches.first().range.first))
            cursor = matches.first().range.first
        }

        for (i in matches.indices) {
            val tag = matches[i]
            val startTagTs = (parseTimeGroups(
                tag.groupValues[1],
                tag.groupValues[2],
                tag.groupValues[3]
            ) - offsetMs).coerceAtLeast(0L)

            val segStart = tag.range.last + 1
            val segEnd = matches.getOrNull(i + 1)?.range?.first ?: rawBody.length
            cursor = segEnd
            if (segStart >= segEnd) continue

            val segment = rawBody.substring(segStart, segEnd)
            val endTagTs = matches.getOrNull(i + 1)?.let { next ->
                (parseTimeGroups(
                    next.groupValues[1],
                    next.groupValues[2],
                    next.groupValues[3]
                ) - offsetMs).coerceAtLeast(startTagTs)
            } ?: 0L

            val leadingTrim = segment.indexOfFirst { !it.isWhitespace() }
            if (leadingTrim < 0) {
                textBuilder.append(segment)
                continue
            }
            textBuilder.append(segment.substring(0, leadingTrim))
            val trimmedSeg = segment.substring(leadingTrim)
            val trailingTrim = trimmedSeg.indexOfLast { !it.isWhitespace() } + 1
            val wordCore = trimmedSeg.substring(0, trailingTrim)
            val startOffset = textBuilder.length
            textBuilder.append(wordCore)
            val endOffset = textBuilder.length
            textBuilder.append(trimmedSeg.substring(trailingTrim))

            if (endOffset > startOffset) {
                rawWords.add(Triple(startTagTs, endTagTs, startOffset to endOffset))
            }
        }

        if (cursor < rawBody.length) {
            textBuilder.append(rawBody.substring(cursor))
        }

        return textBuilder.toString() to rawWords
    }

    private data class MutableWordToken(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val startOffset: Int,
        val endOffset: Int,
        val isBg: Boolean
    )

    fun parseTtml(raw: String): Lyrics {
        val sanitized = raw.replace(Regex("&(?!#?[a-zA-Z0-9]+;)"), "&amp;")
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(InputSource(StringReader(sanitized)))
            val out = mutableListOf<LyricsLine>()
            val paragraphs = document.getElementsByTagNameNS("*", "p")
                .let { if (it.length > 0) it else document.getElementsByTagName("p") }
            for (i in 0 until paragraphs.length) {
                val pNode = paragraphs.item(i) as? Element ?: continue
                val pBeginMs = parseTtmlTimestamp(pNode.getAttribute("begin").ifEmpty { null })
                val pEndAttr = pNode.getAttribute("end").ifEmpty { null }
                val pEndMs = if (pEndAttr != null) parseTtmlTimestamp(pEndAttr) else pBeginMs
                val pAgent = pNode.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "agent")
                    .ifEmpty { pNode.getAttribute("ttm:agent") }
                    .ifEmpty { null }

                val pText = StringBuilder()
                val wordTokens = mutableListOf<MutableWordToken>()
                collectTtmlParagraphChildren(
                    parent = pNode,
                    pBeginMs = pBeginMs,
                    parentBg = false,
                    pText = pText,
                    wordTokens = wordTokens
                )
                buildTtmlLinesForParagraph(
                    pBeginMs = pBeginMs,
                    pEndMs = pEndMs,
                    pAgent = pAgent,
                    rawText = pText.toString(),
                    tokens = wordTokens,
                    out = out
                )
            }
            if (out.isEmpty()) Lyrics.Empty else Lyrics(out.sortedBy { it.timestamp })
        } catch (_: Exception) {
            Lyrics.Empty
        }
    }

    private fun collectTtmlParagraphChildren(
        parent: Node,
        pBeginMs: Long,
        parentBg: Boolean,
        pText: StringBuilder,
        wordTokens: MutableList<MutableWordToken>
    ) {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    val chunk = child.nodeValue.orEmpty()
                    if (chunk.isNotEmpty()) {
                        val normalized = chunk.replace(Regex("""[\r\n\t]+"""), " ")
                        if (normalized == " ") {
                            if (pText.isNotEmpty() && !pText.last().isWhitespace()) {
                                pText.append(' ')
                            }
                        } else {
                            pText.append(normalized)
                        }
                    }
                }

                Node.ELEMENT_NODE -> {
                    val el = child as Element
                    val localName = el.localName ?: el.nodeName.substringAfter(':')
                    if (localName == "br") {
                        pText.append('\n')
                    } else if (localName == "span") {
                        val role = el.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role")
                            .ifEmpty { el.getAttribute("ttm:role") }
                        val isBg = parentBg || role == "x-bg"
                        val beginAttr = el.getAttribute("begin").ifEmpty { null }
                        val endAttr = el.getAttribute("end").ifEmpty { null }
                        val sBegin = if (beginAttr != null) parseTtmlTimestamp(beginAttr) else pBeginMs
                        val sEnd = if (endAttr != null) parseTtmlTimestamp(endAttr) else sBegin
                        val startOffset = pText.length
                        val tokensBefore = wordTokens.size
                        collectTtmlParagraphChildren(el, sBegin, isBg, pText, wordTokens)
                        val endOffset = pText.length
                        if (wordTokens.size == tokensBefore && endOffset > startOffset) {
                            wordTokens.add(
                                MutableWordToken(
                                    startTimeMs = sBegin,
                                    endTimeMs = sEnd.coerceAtLeast(sBegin),
                                    startOffset = startOffset,
                                    endOffset = endOffset,
                                    isBg = isBg
                                )
                            )
                        }
                    } else {
                        collectTtmlParagraphChildren(el, pBeginMs, parentBg, pText, wordTokens)
                    }
                }
            }
        }
    }

    private fun buildTtmlLinesForParagraph(
        pBeginMs: Long,
        pEndMs: Long,
        pAgent: String?,
        rawText: String,
        tokens: List<MutableWordToken>,
        out: MutableList<LyricsLine>
    ) {
        val mainTokens = tokens.filter { !it.isBg }
        val bgTokens = tokens.filter { it.isBg }

        if (mainTokens.isNotEmpty() && bgTokens.isNotEmpty()) {
            appendTokenSliceLine(
                agent = pAgent,
                rawText = rawText,
                tokens = mainTokens,
                fallbackBegin = pBeginMs,
                fallbackEnd = pEndMs,
                stripBgParens = false,
                out = out
            )
            appendTokenSliceLine(
                agent = "bg",
                rawText = rawText,
                tokens = bgTokens,
                fallbackBegin = bgTokens.minOf { it.startTimeMs },
                fallbackEnd = bgTokens.maxOf { it.endTimeMs },
                stripBgParens = true,
                out = out
            )
            return
        }

        val fullTrimmed = trimWithOffsetShift(rawText)
        if (fullTrimmed.first.isEmpty()) return
        val shift = fullTrimmed.second
        val lineText = fullTrimmed.first
        val isAllBg = tokens.isNotEmpty() && bgTokens.size == tokens.size
        val mappedWords = tokens.mapNotNull { token ->
            val s = (token.startOffset - shift).coerceIn(0, lineText.length)
            val e = (token.endOffset - shift).coerceIn(0, lineText.length)
            if (e > s) {
                LyricsWord(token.startTimeMs, token.endTimeMs.coerceAtLeast(token.startTimeMs), s, e)
            } else {
                null
            }
        }
        val finalText: String
        val finalWords: List<LyricsWord>
        if (isAllBg && lineText.startsWith("(") && lineText.endsWith(")") && lineText.length > 2) {
            finalText = lineText.substring(1, lineText.length - 1).trim()
            val innerShift = lineText.indexOfFirst { it != '(' && !it.isWhitespace() }.coerceAtLeast(1)
            finalWords = mappedWords.mapNotNull { w ->
                val s = (w.startOffset - innerShift).coerceIn(0, finalText.length)
                val e = (w.endOffset - innerShift).coerceIn(0, finalText.length)
                if (e > s) w.copy(startOffset = s, endOffset = e) else null
            }
        } else {
            finalText = lineText
            finalWords = mappedWords
        }
        if (finalText.isNotEmpty()) {
            val resolvedEnd = finalWords.maxOfOrNull { it.endTimeMs } ?: pEndMs.coerceAtLeast(pBeginMs)
            out.add(
                LyricsLine(
                    timestamp = pBeginMs,
                    agent = if (isAllBg) "bg" else pAgent,
                    text = finalText,
                    background = null,
                    endTimestamp = resolvedEnd,
                    words = finalWords
                )
            )
        }
    }

    private fun appendTokenSliceLine(
        agent: String?,
        rawText: String,
        tokens: List<MutableWordToken>,
        fallbackBegin: Long,
        fallbackEnd: Long,
        stripBgParens: Boolean,
        out: MutableList<LyricsLine>
    ) {
        if (tokens.isEmpty()) return
        val sb = StringBuilder()
        val outWords = mutableListOf<LyricsWord>()
        var prevOrigEnd = tokens.first().startOffset

        for (i in tokens.indices) {
            val t = tokens[i]
            val sOrig = t.startOffset.coerceIn(0, rawText.length)
            val eOrig = t.endOffset.coerceIn(sOrig, rawText.length)
            if (eOrig <= sOrig) continue

            if (i > 0 && sOrig > prevOrigEnd) {
                val gap = rawText.substring(prevOrigEnd, sOrig)
                if (gap.any { it.isWhitespace() }) {
                    sb.append(' ')
                }
            }

            var chunk = rawText.substring(sOrig, eOrig)
            if (stripBgParens) {
                if (i == 0 && chunk.startsWith("(")) {
                    chunk = chunk.substring(1)
                }
                if (i == tokens.lastIndex && chunk.endsWith(")")) {
                    chunk = chunk.substring(0, chunk.length - 1)
                }
            }
            if (chunk.isNotEmpty()) {
                val wStart = sb.length
                sb.append(chunk)
                val wEnd = sb.length
                outWords.add(LyricsWord(t.startTimeMs, t.endTimeMs.coerceAtLeast(t.startTimeMs), wStart, wEnd))
            }
            prevOrigEnd = eOrig
        }

        val finalText = sb.toString().trim()
        if (finalText.isEmpty()) return
        val leadShift = sb.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val adjustedWords = outWords.mapNotNull { w ->
            val s = (w.startOffset - leadShift).coerceIn(0, finalText.length)
            val e = (w.endOffset - leadShift).coerceIn(0, finalText.length)
            if (e > s) w.copy(startOffset = s, endOffset = e) else null
        }
        val startMs = tokens.minOfOrNull { it.startTimeMs } ?: fallbackBegin
        val endMs = tokens.maxOfOrNull { it.endTimeMs } ?: fallbackEnd.coerceAtLeast(startMs)
        out.add(
            LyricsLine(
                timestamp = startMs,
                agent = agent,
                text = finalText,
                background = null,
                endTimestamp = endMs,
                words = adjustedWords
            )
        )
    }

    private fun trimWithOffsetShift(raw: String): Pair<String, Int> {
        val leading = raw.indexOfFirst { !it.isWhitespace() }
        if (leading < 0) return "" to 0
        val trailing = raw.indexOfLast { !it.isWhitespace() } + 1
        return raw.substring(leading, trailing) to leading
    }

    private fun parsePlain(raw: String): Lyrics {
        val nonEmpty = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return Lyrics.Empty
        return Lyrics(
            nonEmpty.mapIndexed { index, text ->
                LyricsLine(
                    timestamp = index * 4000L,
                    agent = null,
                    text = text,
                    background = null
                )
            }
        )
    }

    private fun parseTimeGroups(minStr: String, secStr: String, fracStr: String): Long {
        val minutes = minStr.toLongOrNull() ?: 0L
        val seconds = secStr.toLongOrNull() ?: 0L
        val millis = when (fracStr.length) {
            0 -> 0L
            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
            else -> fracStr.take(3).toLongOrNull() ?: 0L
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun parseTtmlTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val value = raw.trim()
        if (value.endsWith("ms")) {
            return value.dropLast(2).toDoubleOrNull()?.roundToLong() ?: 0L
        }
        if (value.endsWith("s")) {
            return ((value.dropLast(1).toDoubleOrNull() ?: 0.0) * 1000.0).roundToLong()
        }
        val parts = value.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toDoubleOrNull() ?: 0.0
                val m = parts[1].toDoubleOrNull() ?: 0.0
                val s = parts[2].toDoubleOrNull() ?: 0.0
                ((h * 3600.0 + m * 60.0 + s) * 1000.0).roundToLong()
            }
            2 -> {
                val m = parts[0].toDoubleOrNull() ?: 0.0
                val s = parts[1].toDoubleOrNull() ?: 0.0
                ((m * 60.0 + s) * 1000.0).roundToLong()
            }
            1 -> ((parts[0].toDoubleOrNull() ?: 0.0) * 1000.0).roundToLong()
            else -> 0L
        }
    }
}
