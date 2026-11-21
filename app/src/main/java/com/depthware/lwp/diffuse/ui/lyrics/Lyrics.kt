package com.depthware.lwp.diffuse.ui.lyrics

data class LyricsWord(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val startOffset: Int,
    val endOffset: Int
)

@JvmInline
value class Lyrics(val lyrics: List<LyricsLine>) {
    companion object {
        val Empty = Lyrics(emptyList())
    }
}

data class LyricsLine(
    val timestamp: Long,
    val agent: String?,
    val text: String,
    val background: String?,
    val endTimestamp: Long = timestamp,
    val words: List<LyricsWord> = emptyList(),
    val backgroundTimestamp: Long = timestamp,
    val backgroundEndTimestamp: Long = timestamp,
    val backgroundWords: List<LyricsWord> = emptyList()
) {
    val isMain: Boolean
        get() = agent == null ||
            agent == "v1" ||
            agent == "1" ||
            agent == "M"
}
