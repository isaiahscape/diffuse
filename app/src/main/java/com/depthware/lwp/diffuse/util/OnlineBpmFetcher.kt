package com.depthware.lwp.diffuse.util

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale

data class OnlineBpmResult(val bpm: Float, val source: String)

/**
 * Fetches track tempo online without requiring API keys:
 * 1. Normalizes track metadata via Apple Music / iTunes Search (with Cider package awareness).
 * 2. Looks up exact BPM via Deezer's public Search + Track API.
 * 3. Falls back to ReccoBeats analysis of the 30s MP3 preview when Deezer returns bpm == 0.
 */
object OnlineBpmFetcher {

    private val middotNoise = Regex("""\s*[·•]\s+.*$""")
    private val parenNoise = Regex("""\s*[\(\[].*?[\)\]]""")
    private val featNoise = Regex("""\s+(feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val nonWord = Regex("""[^\p{L}\p{N}\s]+""")
    private val multiSpace = Regex("""\s+""")

    private const val MAX_CACHE_ENTRIES = 64
    private val cache: MutableMap<String, OnlineBpmResult> = Collections.synchronizedMap(
        object : LinkedHashMap<String, OnlineBpmResult>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OnlineBpmResult>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    fun lookup(title: String, artist: String, packageName: String?): OnlineBpmResult? {
        val cleanTitle = cleanTrackText(title)
        val cleanArtist = cleanTrackText(artist)
        if (cleanTitle.isEmpty()) return null

        val key = "${normalizeKey(cleanArtist)}|${normalizeKey(cleanTitle)}"
        cache[key]?.let { cached ->
            return if (cached.bpm > 20.0f) cached else null
        }

        val resolved = resolveUncached(cleanTitle, cleanArtist, packageName.orEmpty())
        cache[key] = resolved ?: OnlineBpmResult(0.0f, "")
        return resolved?.takeIf { it.bpm > 20.0f }
    }

    private fun resolveUncached(title: String, artist: String, packageName: String): OnlineBpmResult? {
        val isAppleMusic = packageName.contains("apple", ignoreCase = true)
        val isCider = packageName.contains("cider", ignoreCase = true)

        val itunes = tryItunesSearch(artist, title)
        var deezer = tryDeezerSearch(artist, title)

        if ((deezer == null || deezer.bpm <= 20.0f) && itunes != null) {
            val itArtist = cleanTrackText(itunes.first)
            val itTitle = cleanTrackText(itunes.second)
            if (!itTitle.equals(title, ignoreCase = true) || !itArtist.equals(artist, ignoreCase = true)) {
                val retry = tryDeezerSearch(itArtist, itTitle)
                if (retry != null) deezer = retry
            }
        }

        val prefix = when {
            isCider -> "Cider + "
            isAppleMusic || itunes != null -> "Apple Music + "
            else -> ""
        }

        if (deezer != null && deezer.bpm > 20.0f) {
            return OnlineBpmResult(deezer.bpm, "${prefix}Deezer")
        }

        val previewUrl = deezer?.previewUrl
        if (!previewUrl.isNullOrEmpty()) {
            val tempo = tryReccoBeatsPreview(previewUrl)
            if (tempo > 20.0f) {
                return OnlineBpmResult(tempo, "${prefix}ReccoBeats")
            }
        }
        return null
    }

    private fun tryItunesSearch(artist: String, title: String): Pair<String, String>? {
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
                if (isTrackMatch(artist, title, candArtist, candTitle)) {
                    return Pair(candArtist, candTitle)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private data class DeezerMatch(val id: Long, val bpm: Float, val previewUrl: String)

    private fun tryDeezerSearch(artist: String, title: String): DeezerMatch? {
        val term = if (artist.isEmpty()) title else "$artist $title"
        val searchJson = httpGetString(
            "https://api.deezer.com/search?limit=5&q=${urlEncode(term)}"
        ) ?: return null
        return try {
            val data = JSONObject(searchJson).optJSONArray("data") ?: return null
            var matchId = -1L
            var fallbackPreview = ""
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val candArtist = item.optJSONObject("artist")?.optString("name").orEmpty()
                val candTitle = item.optString("title")
                if (isTrackMatch(artist, title, candArtist, candTitle)) {
                    matchId = item.optLong("id", -1L)
                    fallbackPreview = item.optString("preview")
                    break
                }
            }
            if (matchId <= 0L) return null

            val trackJson = httpGetString("https://api.deezer.com/track/$matchId") ?: return null
            val trackObj = JSONObject(trackJson)
            val bpm = trackObj.optDouble("bpm", 0.0).toFloat()
            val preview = trackObj.optString("preview").ifEmpty { fallbackPreview }
            DeezerMatch(matchId, bpm, preview)
        } catch (_: Exception) {
            null
        }
    }

    private fun tryReccoBeatsPreview(previewUrl: String): Float {
        val mp3Bytes = httpGetBytes(previewUrl) ?: return 0.0f
        if (mp3Bytes.size < 1024 || mp3Bytes.size > 4_800_000) return 0.0f

        val boundary = "----DiffuseBpmBoundary${System.currentTimeMillis()}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://api.reccobeats.com/v1/analysis/audio-features").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", "DiffuseAndroid/1.3")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"audioFile\"; filename=\"preview.mp3\"\r\n")
                out.writeBytes("Content-Type: audio/mpeg\r\n\r\n")
                out.write(mp3Bytes)
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            if (conn.responseCode !in 200..299) return 0.0f
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val tempo = JSONObject(body).optDouble("tempo", 0.0).toFloat()
            if (tempo in 20.0f..250.0f) tempo else 0.0f
        } catch (_: Exception) {
            0.0f
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpGetString(urlStr: String): String? {
        val bytes = httpGetBytes(urlStr) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun httpGetBytes(urlStr: String): ByteArray? {
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
                    if (out.size() > 4_800_000) return null
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun isTrackMatch(
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

    private fun cleanTrackText(text: String): String {
        var s = middotNoise.replace(text, "")
        s = parenNoise.replace(s, "")
        s = featNoise.replace(s, "")
        return s.trim()
    }

    private fun normalizeKey(text: String): String {
        val lower = nonWord.replace(text.lowercase(Locale.ROOT), " ")
        return multiSpace.replace(lower, " ").trim()
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
