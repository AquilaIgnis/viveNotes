package com.vivenotes.model

/**
 * A video URL sitting in a block's own text, and the video it names.
 *
 * [start] and [end] index the string that was scanned — a paragraph's [Block.editorText] — so an
 * offset here is an offset the editor can set a span over. The same contract
 * `AutoEquationCandidate` has, and for the same reason: the preview is drawn *over* text that is
 * still there, so the two numbers have to mean exactly what `setSpan` means by them.
 *
 * [url] is kept beside [videoId] rather than rebuilt from it, because it carries the parts the id
 * throws away — a `t=90` start offset most of all. Tapping the card opens what the writer pasted,
 * not a canonical form of it that quietly drops them back at zero.
 */
data class VideoLink(
    val start: Int,
    val end: Int,
    val videoId: String,
    val url: String,
)

/**
 * Every YouTube URL in [text], in the order they appear.
 *
 * **Whitespace-delimited tokens, not a URL regular expression.** A pattern loose enough to find
 * bare `youtube.com/...` in prose is also loose enough to match half of one inside a longer word,
 * and the failure mode is a thumbnail drawn over text that is not a link. Splitting on whitespace
 * first means every candidate is something the writer typed as a standalone thing, and the host
 * allow-list in [youTubeVideoId] does the rest of the work.
 *
 * Deliberately Android-free — no `android.net.Uri` — so it is covered by JVM tests rather than
 * needing a device, which is the project's rule for anything that is pure string work.
 */
fun findVideoLinks(text: String): List<VideoLink> {
    val found = mutableListOf<VideoLink>()
    var index = 0
    while (index < text.length) {
        if (text[index].isWhitespace()) {
            index++
            continue
        }
        var end = index
        while (end < text.length && !text[end].isWhitespace()) end++
        val bounds = text.urlBoundsIn(index, end)
        if (bounds != null) {
            val token = text.substring(bounds.first, bounds.second)
            youTubeVideoId(token)?.let { id ->
                found += VideoLink(start = bounds.first, end = bounds.second, videoId = id, url = token)
            }
        }
        index = end
    }
    return found
}

/**
 * The eleven-character video id [url] names, or null if it names no YouTube video.
 *
 * Every accepted form, because all of them get pasted: `watch?v=`, the `youtu.be` short link, and
 * the `/shorts/`, `/embed/`, `/live/` and `/v/` paths. A scheme is optional — people paste
 * `youtube.com/watch?v=…` out of an address bar without one — and the host allow-list rather than
 * the scheme is what keeps this from matching arbitrary text.
 *
 * **The id is validated to exactly eleven characters of `[A-Za-z0-9_-]`, and that is load-bearing
 * twice.** It is what stops a mistyped link from being previewed as a video that does not exist,
 * and it is what makes the id safe to use as a filename — `com.vivenotes.data.VideoThumbnailStore`
 * names cache files by it, so anything that could contain a `/` or a `..` must never get that far.
 */
fun youTubeVideoId(url: String): String? {
    val withoutScheme = url.substringAfter("://", missingDelimiterValue = url)
    // Credentials before an `@` would let `youtube.com@evil.example/watch?v=…` read as a YouTube
    // host by the naive split below. Nothing legitimate carries them, so a token that has one is
    // simply not a link this understands.
    if (withoutScheme.substringBefore('/').contains('@')) return null

    val hostEnd = withoutScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        .let { if (it < 0) withoutScheme.length else it }
    val host = withoutScheme.substring(0, hostEnd).substringBefore(':').lowercase()
    if (host !in YOUTUBE_HOSTS) return null

    val rest = withoutScheme.substring(hostEnd)
    val path = rest.substringBefore('?').substringBefore('#').trim('/')
    val query = rest.substringAfter('?', missingDelimiterValue = "").substringBefore('#')

    val candidate = when {
        // `youtu.be/<id>` — the whole path is the id.
        host == "youtu.be" -> path.substringBefore('/')
        path == "watch" -> query.queryParameter("v")
        else -> {
            val segments = path.split('/')
            if (segments.size >= 2 && segments[0] in VIDEO_PATH_PREFIXES) segments[1] else null
        }
    }
    return candidate?.takeIf(::isVideoId)
}

/** Whether [value] is shaped like a YouTube video id. See [youTubeVideoId] for why this is strict. */
private fun isVideoId(value: String): Boolean =
    value.length == VIDEO_ID_LENGTH && value.all { char ->
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-'
    }

private fun String.queryParameter(name: String): String? = split('&')
    .firstOrNull { it.startsWith("$name=") }
    ?.substringAfter('=')

/**
 * The URL inside a whitespace-delimited token, once the sentence around it is discounted.
 *
 * `watch this: https://youtu.be/dQw4w9WgXcQ.` ends in a full stop that belongs to the sentence, and
 * `(https://youtu.be/dQw4w9WgXcQ)` is wrapped in brackets the same way. Both ends are trimmed
 * unconditionally rather than by matching pairs across the token, because a YouTube URL contains
 * none of these characters in the first place and [youTubeVideoId] rejects anything that reaches it
 * malformed — so over-trimming costs nothing, while the bracket-matching version needed to reason
 * about the prose either side of the link to decide.
 *
 * Returns null for a token that is nothing but punctuation. The range is half-open, matching what
 * `setSpan` means by its two offsets, which is what these numbers eventually become.
 */
private fun String.urlBoundsIn(start: Int, end: Int): Pair<Int, Int>? {
    var first = start
    var last = end
    while (first < last && this[first] in LEADING_PUNCTUATION) first++
    while (last > first && this[last - 1] in TRAILING_PUNCTUATION) last--
    return if (last > first) first to last else null
}

private val YOUTUBE_HOSTS = setOf(
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "music.youtube.com",
    "youtube-nocookie.com",
    "www.youtube-nocookie.com",
    "youtu.be",
    "www.youtu.be",
)

/** Paths whose next segment is the video id. */
private val VIDEO_PATH_PREFIXES = setOf("shorts", "embed", "live", "v")

private const val VIDEO_ID_LENGTH = 11

private val LEADING_PUNCTUATION = setOf('(', '[', '{', '<', '"', '\'', '“', '‘')

private val TRAILING_PUNCTUATION =
    setOf('.', ',', ';', ':', '!', '?', '"', '\'', '”', '’', ')', ']', '}', '>')
