package com.depthware.lwp.diffuse.util

import com.depthware.lwp.diffuse.ui.lyrics.Lyrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale

data class LyricsLookupResult(
    val lyrics: Lyrics,
    val isSynced: Boolean,
    val hasWordTiming: Boolean = false
)

object LyricsRepository {

    private val middotSuffixNoise = Regex("""\s*[·•]\s+.*$""")
    private val parenNoise = Regex("""\s*[\(\[].*?[\)\]]""")
    private val featNoise = Regex("""\s+(feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val nonWord = Regex("""[^\p{L}\p{N}\s]+""")
    private val multiSpace = Regex("""\s+""")

    private const val MAX_CACHE_ENTRIES = 64
    private val cache: MutableMap<String, LyricsLookupResult> = Collections.synchronizedMap(
        object : LinkedHashMap<String, LyricsLookupResult>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsLookupResult>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    fun fetchLyrics(
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): LyricsLookupResult {
        val cleanTitle = cleanTrackText(title)
        val cleanArtist = cleanTrackText(stripSourceSuffix(artist))
        val cleanAlbum = cleanTrackText(album)
        if (cleanTitle.isEmpty()) return LyricsLookupResult(Lyrics.Empty, false, false)

        val key = "${normalizeKey(cleanArtist)}|${normalizeKey(cleanTitle)}"
        cache[key]?.let { return it }

        val ttmlResult = fetchOnlineTtml(cleanTitle, cleanArtist)
        if (ttmlResult != null && ttmlResult.hasWordTiming) {
            cache[key] = ttmlResult
            return ttmlResult
        }

        val lrcLibResult = fetchFromLrcLib(cleanTitle, cleanArtist, cleanAlbum, durationMs)
        val chosen = when {
            lrcLibResult.hasWordTiming -> lrcLibResult
            ttmlResult != null && ttmlResult.isSynced -> ttmlResult
            lrcLibResult.lyrics.lyrics.isNotEmpty() -> lrcLibResult
            ttmlResult != null -> ttmlResult
            else -> LyricsLookupResult(Lyrics.Empty, false, false)
        }
        cache[key] = chosen
        return chosen
    }

    private fun fetchOnlineTtml(title: String, artist: String): LyricsLookupResult? {
        tryBoiduTtml(title, artist)?.let { return it }

        val canonical = tryItunesCanonicalize(artist, title)
        if (canonical != null) {
            val cArtist = cleanTrackText(canonical.first)
            val cTitle = cleanTrackText(canonical.second)
            if (!cTitle.equals(title, ignoreCase = true) || !cArtist.equals(artist, ignoreCase = true)) {
                tryBoiduTtml(cTitle, cArtist)?.let { return it }
            }
        }
        return null
    }

    private fun tryBoiduTtml(title: String, artist: String): LyricsLookupResult? {
        if (title.isBlank() || artist.isBlank()) return null
        val url =
            "https://lyrics-api.boidu.dev/getLyrics?s=${urlEncode(title)}&a=${urlEncode(artist)}"
        val json = httpGetString(url) ?: return null
        return try {
            val ttml = JSONObject(json).optString("ttml")
            if (ttml.isBlank() || ttml == "null") return null
            val parsed = LyricsParser.parseTtml(ttml)
            if (parsed.lyrics.isEmpty()) return null
            val hasWords = parsed.lyrics.any { it.words.isNotEmpty() }
            LyricsLookupResult(parsed, isSynced = true, hasWordTiming = hasWords)
        } catch (_: Exception) {
            null
        }
    }

    private fun tryItunesCanonicalize(artist: String, title: String): Pair<String, String>? {
        val term = if (artist.isEmpty()) title else "$artist $title"
        val jsonStr = httpGetString(
            "https://itunes.apple.com/search?entity=song&limit=5&term=${urlEncode(term)}"
        ) ?: return null
        return try {
            val results = JSONObject(jsonStr).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val candArtist = item.optString("artistName")
                val candTitle = item.optString("trackName")
                if (isCandidateMatch(artist, title, candArtist, candTitle)) {
                    return candArtist to candTitle
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromLrcLib(
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): LyricsLookupResult {
        if (artist.isNotEmpty()) {
            val params = buildString {
                append("track_name=").append(urlEncode(title))
                append("&artist_name=").append(urlEncode(artist))
                if (album.isNotEmpty()) {
                    append("&album_name=").append(urlEncode(album))
                }
                if (durationMs > 1000L) {
                    append("&duration=").append(durationMs / 1000L)
                }
            }
            val directJson = httpGetString("https://lrclib.net/api/get?$params")
            if (!directJson.isNullOrBlank()) {
                extractFromObject(JSONObject(directJson))?.let { return it }
            }
        }

        val searchQuery = if (artist.isNotEmpty()) {
            "track_name=${urlEncode(title)}&artist_name=${urlEncode(artist)}"
        } else {
            "q=${urlEncode(title)}"
        }
        val searchJson = httpGetString("https://lrclib.net/api/search?$searchQuery")
        if (!searchJson.isNullOrBlank()) {
            val array = JSONArray(searchJson)
            var fallbackPlain: LyricsLookupResult? = null
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (!isCandidateMatch(artist, title, obj.optString("artistName"), obj.optString("trackName"))) {
                    continue
                }
                val parsed = extractFromObject(obj) ?: continue
                if (parsed.isSynced) return parsed
                if (fallbackPlain == null) fallbackPlain = parsed
            }
            if (fallbackPlain != null) return fallbackPlain
        }

        return LyricsLookupResult(Lyrics.Empty, false, false)
    }

    private fun extractFromObject(obj: JSONObject): LyricsLookupResult? {
        val synced = obj.optString("syncedLyrics")
        if (synced.isNotBlank() && synced != "null") {
            val parsed = LyricsParser.parseLrc(synced)
            if (parsed.lyrics.isNotEmpty()) {
                val hasWords = parsed.lyrics.any { it.words.isNotEmpty() }
                return LyricsLookupResult(parsed, isSynced = true, hasWordTiming = hasWords)
            }
        }
        val plain = obj.optString("plainLyrics")
        if (plain.isNotBlank() && plain != "null") {
            val parsed = LyricsParser.parse(plain)
            if (parsed.lyrics.isNotEmpty()) {
                return LyricsLookupResult(parsed, isSynced = false, hasWordTiming = false)
            }
        }
        return null
    }

    private fun isCandidateMatch(
        expectedArtist: String,
        expectedTitle: String,
        candArtist: String,
        candTitle: String
    ): Boolean {
        val expTitle = normalizeKey(cleanTrackText(expectedTitle))
        val gotTitle = normalizeKey(cleanTrackText(candTitle))
        if (expTitle.isEmpty() || gotTitle.isEmpty()) return false
        if (!gotTitle.contains(expTitle) && !expTitle.contains(gotTitle)) return false

        val expArtist = normalizeKey(cleanTrackText(expectedArtist))
        if (expArtist.isEmpty()) return true
        val gotArtist = normalizeKey(cleanTrackText(candArtist))
        return gotArtist.isNotEmpty() && (gotArtist.contains(expArtist) || expArtist.contains(gotArtist))
    }

    private fun httpGetString(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "DiffuseAndroid/1.3")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                }
                out.toString("UTF-8")
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun stripSourceSuffix(text: String): String =
        middotSuffixNoise.replace(text, "").trim()

    private fun cleanTrackText(text: String): String {
        var s = parenNoise.replace(text, "")
        s = featNoise.replace(s, "")
        return s.trim()
    }

    private fun normalizeKey(text: String): String {
        val lower = nonWord.replace(text.lowercase(Locale.ROOT), " ")
        return multiSpace.replace(lower, " ").trim()
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
