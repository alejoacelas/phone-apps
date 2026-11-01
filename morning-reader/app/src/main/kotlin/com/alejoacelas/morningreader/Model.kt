package com.alejoacelas.morningreader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A book prepared on the Mac by `./pack` (format in docs/book-pack.md). */
@Serializable
data class BookPack(
    val version: Int = 1,
    val id: String,
    val title: String,
    val author: String = "",
    val language: String = "English",
    val year: Int? = null,
    val origin: String? = null,
    val edition: String = "",
    @SerialName("source_url") val sourceUrl: String = "",
    val summary: String = "",
    @SerialName("playlist_prompts") val playlistPrompts: Map<String, String> = emptyMap(),
    val videos: List<Video> = emptyList(),
    /** Secondary material from trusted sources, best first. Replaces `videos` in packs that have it. */
    val secondary: List<Secondary> = emptyList(),
    val blocks: List<Block> = emptyList(),
)

/** A video, radio episode, essay or book about a book or its author, from a trusted source. */
@Serializable
data class Secondary(
    val kind: String,
    val title: String,
    val creator: String = "",
    val url: String,
    @SerialName("youtube_id") val youtubeId: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    val minutes: Int? = null,
    val why: String = "",
) {
    /** Videos of 10 minutes or less are blocks: they show on Today and count toward the budget. */
    val asBlockVideo: Video?
        get() = if (kind == "video" && youtubeId != null && (minutes ?: 99) <= 10)
            Video(youtubeId, title, creator, (minutes ?: 1) * 60, why) else null
}

@Serializable
data class Block(
    val id: String,
    val order: Int,
    val title: String,
    val hook: String = "",
    val recap: String? = null,
    @SerialName("entry_point") val entryPoint: Boolean = false,
    val words: Int,
    val paragraphs: List<String>,
    @SerialName("playlist_prompt") val playlistPrompt: String = "",
    val videos: List<Video> = emptyList(),
    /** Readable cold on its own: a whole story, poem, essay or self-contained episode. */
    val standalone: Boolean = false,
    val characters: List<Character> = emptyList(),
)

/** A main character in a block, described as of that point in the book (no spoilers). */
@Serializable
data class Character(val name: String, val note: String)

/** A private note on a passage, saved for export and never shown while reading. */
@Serializable
data class Note(
    val at: Long,
    val itemId: String,
    val bookId: String? = null,
    val source: String,
    val blockTitle: String,
    val paragraph: Int,
    val start: Int,
    val end: Int,
    val selection: String,
    val note: String,
)

/** A time the session had minutes left but nothing short enough to fill them. */
@Serializable
data class Shortfall(val day: String, val minutesLeft: Int, val at: Long)

@Serializable
data class Video(
    @SerialName("youtube_id") val youtubeId: String,
    val title: String,
    val channel: String = "",
    val seconds: Int,
    val why: String = "",
)

/** A blog post long enough to be a block, fetched on the phone from the user's feeds. */
@Serializable
data class BlogPost(
    val id: String,
    val feedTitle: String,
    val title: String,
    val url: String,
    val published: Long,
    val hook: String = "",
    val playlistPrompt: String = "",
    val words: Int,
    val paragraphs: List<String>,
)

@Serializable
data class Feed(val url: String, val title: String)

@Serializable
data class Highlight(
    val text: String,
    val answer: String,
    /** "Book · block". */
    val source: String,
    val at: Long,
    /** The typed question for an Ask; null for an Explain. */
    val question: String? = null,
) {
    /** Asks saved before `question` existed kept it as "Q: …" on the answer's first line. */
    val askedQuestion: String?
        get() = question ?: answer.takeIf { it.startsWith("Q: ") }?.substringBefore('\n')?.removePrefix("Q: ")
    val plainAnswer: String
        get() = if (question == null && answer.startsWith("Q: ")) answer.substringAfter('\n') else answer
}

/**
 * The block currently being read. It can always be finished, even past the budget.
 * `activeSeconds` is time in the reader (used to learn reading speed); `awaySeconds` is time
 * spent in other apps mid-block, which counts toward the budget but not the speed.
 */
@Serializable
data class InProgress(
    val itemId: String,
    val activeSeconds: Long = 0,
    val scroll: Int = 0,
    val awaySince: Long? = null,
    val awaySeconds: Long = 0,
)

@Serializable
data class AppState(
    val budgetMinutes: Int = 45,
    val wordsPerMinute: Double = 238.0,
    val speedSamples: Int = 0,
    val day: String = "",
    val usedSecondsToday: Long = 0,
    val inProgress: InProgress? = null,
    val finished: Set<String> = emptySet(),
    val watched: Set<String> = emptySet(),
    val highlights: List<Highlight> = emptyList(),
    val feeds: List<Feed> = emptyList(),
    val seenPostUrls: List<String> = emptyList(),
    val lastBlogRefresh: Long = 0,
    val blogStatus: String = "",
    val shortfalls: List<Shortfall> = emptyList(),
    /** Swiped away today; archived at the next reload. Book ids, post ids or "video-<id>". */
    val pendingArchive: Set<String> = emptySet(),
    val archived: Set<String> = emptySet(),
    /** What to serve next from a book, chosen after a block: "keep" or "later". No entry means no choice. */
    val bookChoices: Map<String, String> = emptyMap(),
    /** Secondary material already opened (by url), so the end of a block offers something new. */
    val opened: Set<String> = emptySet(),
    /** Feature requests not yet accepted by GitHub, oldest first. */
    val pendingRequests: List<FeatureRequest> = emptyList(),
    val lastFiledIssue: Int = 0,
)

/** A feature request typed in Settings, kept until it's filed as a GitHub issue. */
@Serializable
data class FeatureRequest(val text: String, val at: Long)

/** What happens next with a book, chosen at the end of a block. Today's session ranks books by it. */
enum class BookChoice(val key: String, val label: String, val effect: String) {
    Keep("keep", "Keep going", "Its next block leads your session."),
    Later("later", "Maybe later", "It moves to the bottom of your session."),
    Done("done", "Done with this", "Archived. Restore it from the Library."),
}
