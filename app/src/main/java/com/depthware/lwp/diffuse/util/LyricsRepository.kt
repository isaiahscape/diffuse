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

    private const val MXM_APP_ID = "android-player-v1.0"
    private const val MXM_USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP1A)"
    private const val MAX_CACHE_ENTRIES = 64
    private val cache: MutableMap<String, LyricsLookupResult> = Collections.synchronizedMap(
        object : LinkedHashMap<String, LyricsLookupResult>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsLookupResult>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    @Volatile
    private var musixmatchToken: String? = null
    private val tokenLock = Any()

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

        val canonical = if (cleanArtist.isNotEmpty()) {
            tryItunesCanonicalize(cleanArtist, cleanTitle)
        } else {
            null
        }

        val ttmlResult = fetchOnlineTtml(cleanTitle, cleanArtist, canonical)
        if (ttmlResult != null && ttmlResult.hasWordTiming) {
            cache[key] = ttmlResult
            return ttmlResult
        }

        val mxmResult = fetchFromMusixmatch(cleanTitle, cleanArtist, durationMs, canonical)
        if (mxmResult != null && mxmResult.hasWordTiming) {
            cache[key] = mxmResult
            return mxmResult
        }

        val lrcLibResult = fetchFromLrcLib(cleanTitle, cleanArtist, cleanAlbum, durationMs)
        val chosen = when {
            lrcLibResult.hasWordTiming -> lrcLibResult
            ttmlResult != null && ttmlResult.isSynced -> ttmlResult
            mxmResult != null && mxmResult.isSynced -> mxmResult
            lrcLibResult.isSynced -> lrcLibResult
            lrcLibResult.lyrics.lyrics.isNotEmpty() -> lrcLibResult
            mxmResult != null && mxmResult.lyrics.lyrics.isNotEmpty() -> mxmResult
            ttmlResult != null && ttmlResult.lyrics.lyrics.isNotEmpty() -> ttmlResult
            else -> LyricsLookupResult(Lyrics.Empty, false, false)
        }
        if (chosen.lyrics.lyrics.isNotEmpty()) {
            cache[key] = chosen
        }
        return chosen
    }

    private fun fetchOnlineTtml(
        title: String,
        artist: String,
        canonical: Pair<String, String>?
    ): LyricsLookupResult? {
        tryBoiduTtml(title, artist)?.let { return it }
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
            val hasWords = parsed.lyrics.any { it.words.isNotEmpty() || it.backgroundWords.isNotEmpty() }
            LyricsLookupResult(parsed, isSynced = true, hasWordTiming = hasWords)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromMusixmatch(
        title: String,
        artist: String,
        durationMs: Long,
        canonical: Pair<String, String>?
    ): LyricsLookupResult? {
        queryMusixmatch(title, artist, durationMs)?.let { res ->
            if (res.hasWordTiming) return res
        }
        if (canonical != null) {
            val cArtist = cleanTrackText(canonical.first)
            val cTitle = cleanTrackText(canonical.second)
            if (!cTitle.equals(title, ignoreCase = true) || !cArtist.equals(artist, ignoreCase = true)) {
                queryMusixmatch(cTitle, cArtist, durationMs)?.let { return it }
            }
        }
        return queryMusixmatch(title, artist, 0L)
    }

    private fun queryMusixmatch(
        title: String,
        artist: String,
        durationMs: Long
    ): LyricsLookupResult? {
        if (title.isBlank()) return null
        var token = getOrFetchMusixmatchToken(forceRefresh = false) ?: return null

        for (attempt in 0..1) {
            val url = buildString {
                append("https://apic-appmobile.musixmatch.com/ws/1.1/macro.subtitles.get")
                append("?format=json")
                append("&namespace=lyrics_richsynched")
                append("&subtitle_format=mxm")
                append("&app_id=").append(MXM_APP_ID)
                append("&q_track=").append(urlEncode(title))
                if (artist.isNotBlank()) {
                    append("&q_artist=").append(urlEncode(artist))
                }
                if (durationMs > 1000L) {
                    append("&q_duration=").append(durationMs / 1000L)
                }
                append("&optional_calls=track.richsync")
                append("&richsync_compact_type=words")
                append("&usertoken=").append(urlEncode(token))
            }
            val body = httpGetString(url, MXM_USER_AGENT) ?: return null
            try {
                val msg = JSONObject(body).optJSONObject("message") ?: return null
                val statusCode = msg.optJSONObject("header")?.optInt("status_code", 0) ?: 0
                if (statusCode == 401 || statusCode == 402) {
                    if (attempt == 0) {
                        token = getOrFetchMusixmatchToken(forceRefresh = true) ?: return null
                        continue
                    }
                    return null
                }
                if (statusCode != 200) return null
                val macroCalls = msg.optJSONObject("body")?.optJSONObject("macro_calls") ?: return null

                val richBody = macroCalls.optJSONObject("track.richsync.get")
                    ?.optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optJSONObject("richsync")
                    ?.optString("richsync_body")
                if (!richBody.isNullOrBlank() && richBody != "null") {
                    val parsedRich = LyricsParser.parseMusixmatchRichSync(richBody)
                    if (parsedRich.lyrics.isNotEmpty()) {
                        val hasWords = parsedRich.lyrics.any { it.words.isNotEmpty() || it.backgroundWords.isNotEmpty() }
                        return LyricsLookupResult(parsedRich, isSynced = true, hasWordTiming = hasWords)
                    }
                }

                val subList = macroCalls.optJSONObject("track.subtitles.get")
                    ?.optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optJSONArray("subtitle_list")
                if (subList != null && subList.length() > 0) {
                    val subBody = subList.optJSONObject(0)
                        ?.optJSONObject("subtitle")
                        ?.optString("subtitle_body")
                    if (!subBody.isNullOrBlank() && subBody != "null") {
                        val parsedSub = LyricsParser.parseMusixmatchSubtitles(subBody)
                        if (parsedSub.lyrics.isNotEmpty()) {
                            return LyricsLookupResult(parsedSub, isSynced = true, hasWordTiming = false)
                        }
                    }
                }

                val plainBody = macroCalls.optJSONObject("track.lyrics.get")
                    ?.optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optJSONObject("lyrics")
                    ?.optString("lyrics_body")
                if (!plainBody.isNullOrBlank() && plainBody != "null") {
                    val parsedPlain = LyricsParser.parse(plainBody)
                    if (parsedPlain.lyrics.isNotEmpty()) {
                        return LyricsLookupResult(parsedPlain, isSynced = false, hasWordTiming = false)
                    }
                }
                return null
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun getOrFetchMusixmatchToken(forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            musixmatchToken?.let { return it }
        }
        synchronized(tokenLock) {
            if (!forceRefresh) {
                musixmatchToken?.let { return it }
            }
            val jsonStr = httpGetString(
                "https://apic-appmobile.musixmatch.com/ws/1.1/token.get?app_id=$MXM_APP_ID",
                MXM_USER_AGENT
            ) ?: return null
            return try {
                val token = JSONObject(jsonStr)
                    .optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optString("user_token")
                if (!token.isNullOrBlank() && !token.all { it == '0' }) {
                    musixmatchToken = token
                    token
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
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
                val hasWords = parsed.lyrics.any { it.words.isNotEmpty() || it.backgroundWords.isNotEmpty() }
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

    private fun httpGetString(urlStr: String, userAgent: String = "DiffuseAndroid/1.3"): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 7000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
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
