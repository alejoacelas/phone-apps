package com.alejoacelas.morningreader

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.time.LocalDate
import kotlin.math.ceil

/** Anything readable in the reader: a book block or a blog post. */
data class ReadItem(
    val id: String,
    val source: String,
    val title: String,
    val hook: String,
    val recap: String?,
    val paragraphs: List<String>,
    val words: Int,
    val playlistPrompts: List<Pair<String, String>>,
    val videos: List<Video>,
    val bookId: String? = null,
    val order: Int? = null,
    val url: String? = null,
    val language: String? = null,
    val characters: List<Character> = emptyList(),
)

object Store {
    private const val TAG = "Store"
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private var saveJob: Job? = null

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state
    private val _books = MutableStateFlow<List<BookPack>>(emptyList())
    val books: StateFlow<List<BookPack>> = _books
    private val _posts = MutableStateFlow<List<BlogPost>>(emptyList())
    val posts: StateFlow<List<BlogPost>> = _posts
    val loaded = MutableStateFlow(false)
    /** Notes from exports/notes.jsonl, newest first. */
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    private val stateFile get() = File(appContext.filesDir, "state.json")
    private val postsDir get() = File(appContext.filesDir, "posts").apply { mkdirs() }
    val packsDir: File get() = File(appContext.getExternalFilesDir(null), "packs").apply { mkdirs() }
    private val importDir get() = File(appContext.getExternalFilesDir(null), "import").apply { mkdirs() }

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch { reload() }
    }

    private var packsSignature = ""

    private fun currentSignature(): String =
        packsDir.listFiles().orEmpty().sortedBy { it.name }.joinToString { "${it.name}:${it.length()}:${it.lastModified()}" } +
            "|" + importDir.listFiles { f -> f.extension.lowercase() in setOf("opml", "xml") }.orEmpty().size

    /** Reloads only if packs or feed lists were pushed since the last load (called when the app comes forward). */
    fun reloadIfChanged() {
        if (!::appContext.isInitialized) return
        scope.launch { if (loaded.value && currentSignature() != packsSignature) reload() }
    }

    /** Reads saved state, book packs pushed from the Mac, saved posts and any pushed OPML. */
    suspend fun reload() = withContext(Dispatchers.IO) {
        if (!loaded.value && stateFile.exists()) {
            runCatching { _state.value = json.decodeFromString<AppState>(stateFile.readText()) }
                .onFailure { Log.e(TAG, "state unreadable", it) }
        }
        _books.value = packsDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
            .mapNotNull { f ->
                runCatching { json.decodeFromString<BookPack>(f.readText()) }
                    .onFailure { Log.e(TAG, "bad pack ${f.name}", it) }.getOrNull()
                    ?.let { p -> if (p.secondary.isEmpty()) p else p.copy(videos = p.secondary.mapNotNull { it.asBlockVideo }) }
            }
        _posts.value = postsDir.listFiles().orEmpty().mapNotNull {
            runCatching { json.decodeFromString<BlogPost>(it.readText()) }.getOrNull()
        }.sortedByDescending { it.published }
        importDir.listFiles { f -> f.extension.lowercase() in setOf("opml", "xml") }.orEmpty().forEach { f ->
            importOpml(f.readText())
            f.renameTo(File(f.parentFile, f.name + ".imported"))
        }
        _notes.value = File(exportsDir, "notes.jsonl").takeIf { it.exists() }?.readLines().orEmpty()
            .mapNotNull { line -> runCatching { json.decodeFromString<Note>(line) }.getOrNull() }.sortedByDescending { it.at }
        applyArchive()
        packsSignature = currentSignature()
        loaded.value = true
    }

    /** Applies a change, starting a new day's budget when the date rolls over, and saves. */
    fun update(change: (AppState) -> AppState) {
        synchronized(this) {
            val today = LocalDate.now().toString()
            var next = change(_state.value)
            if (next.day != today) next = next.copy(day = today, usedSecondsToday = 0)
            _state.value = next
        }
        // Reading ticks update state every second, so save at most every two seconds.
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(2000)
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(json.encodeToString(AppState.serializer(), _state.value))
            tmp.renameTo(stateFile)
        }
    }

    /** Writes state at once, for changes that must survive the process dying right after. */
    fun saveNow() = synchronized(this) {
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(json.encodeToString(AppState.serializer(), _state.value))
        tmp.renameTo(stateFile)
    }

    fun savePost(post: BlogPost) {
        File(postsDir, post.id + ".json").writeText(json.encodeToString(BlogPost.serializer(), post))
        _posts.value = (_posts.value.filter { it.id != post.id } + post).sortedByDescending { it.published }
    }

    fun prunePosts(keep: (BlogPost) -> Boolean) {
        val (kept, dropped) = _posts.value.partition(keep)
        dropped.forEach { File(postsDir, it.id + ".json").delete() }
        _posts.value = kept
    }

    fun importOpml(xml: String): Int {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val feeds = doc.select("outline[xmlUrl]").map {
            Feed(it.attr("xmlUrl"), it.attr("title").ifBlank { it.attr("text") })
        }.filterNot { it.url.contains("xkcd.com") } // comics never reach a 5-minute read
        val known = _state.value.feeds.map { it.url }.toSet()
        val added = feeds.filter { it.url !in known }.distinctBy { it.url }
        update { it.copy(feeds = it.feeds + added) }
        if (added.isNotEmpty()) Blogs.refreshNow { }
        return added.size
    }

    fun importOpml(uri: Uri): Int =
        appContext.contentResolver.openInputStream(uri)?.use { importOpml(it.reader().readText()) } ?: 0

    // --- Reading items, estimates and the budget rule ---

    fun minutesFor(words: Int): Int = maxOf(1, ceil(words / _state.value.wordsPerMinute).toInt())

    fun usedMinutes(): Double = _state.value.usedSecondsToday / 60.0

    fun minutesLeft(): Int = (_state.value.budgetMinutes - usedMinutes()).toInt().coerceAtLeast(0)

    /** A block may start only if it fits in what's left today; the one in progress can always finish. */
    fun canStart(itemId: String, minutes: Int): Boolean {
        val s = _state.value
        return s.inProgress?.itemId == itemId || usedMinutes() + minutes <= s.budgetMinutes + 0.001
    }

    /** Time away from a block counts as reading time when you come back to it, up to this cap. */
    const val AWAY_CAP_SECONDS = 5 * 60L

    fun leftBlock(itemId: String) = update { s ->
        val p = s.inProgress?.takeIf { it.itemId == itemId && it.awaySince == null } ?: return@update s
        s.copy(inProgress = p.copy(awaySince = System.currentTimeMillis()))
    }

    fun returnedToBlock(itemId: String) = update { s ->
        val p = s.inProgress?.takeIf { it.itemId == itemId } ?: return@update s
        val since = p.awaySince ?: return@update s
        val credit = ((System.currentTimeMillis() - since) / 1000).coerceIn(0, AWAY_CAP_SECONDS)
        s.copy(
            usedSecondsToday = s.usedSecondsToday + credit,
            inProgress = p.copy(awaySince = null, awaySeconds = p.awaySeconds + credit),
        )
    }

    /** Phone-side exports, readable over adb at Android/data/<package>/files/exports/. */
    val exportsDir: File get() = File(appContext.getExternalFilesDir(null), "exports").apply { mkdirs() }

    fun saveNote(note: Note) = scope.launch {
        synchronized(this@Store) {
            File(exportsDir, "notes.jsonl").appendText(json.encodeToString(Note.serializer(), note) + "\n")
        }
        _notes.value = listOf(note) + _notes.value
    }

    /** Writes Ask answers and shortfalls next to the notes so `./pack pull` gets what the Notes tab shows. */
    fun writeExports() = scope.launch {
        val s = _state.value
        val pretty = Json { prettyPrint = true; encodeDefaults = true }
        val asks = s.highlights.mapNotNull { h ->
            h.askedQuestion?.let { Highlight(h.text, h.plainAnswer, h.source, h.at, it) }
        }
        File(exportsDir, "asks.json").writeText(
            pretty.encodeToString(kotlinx.serialization.builtins.ListSerializer(Highlight.serializer()), asks))
        File(exportsDir, "shortfalls.json").writeText(
            pretty.encodeToString(kotlinx.serialization.builtins.ListSerializer(Shortfall.serializer()), s.shortfalls))
    }

    /** What a swipe on this card archives: the whole book, the whole post, or one video. */
    fun archiveKey(card: Card): String = when (card) {
        is Card.Reading -> card.item.bookId ?: card.item.id
        is Card.Watch -> card.key
    }

    fun toggleArchive(key: String) = update { s ->
        s.copy(pendingArchive = if (key in s.pendingArchive) s.pendingArchive - key else s.pendingArchive + key)
    }

    fun restore(key: String) = update { s -> s.copy(archived = s.archived - key, pendingArchive = s.pendingArchive - key) }

    /** The choice made for a book after a block; an archived book counts as "done with this". */
    fun choiceFor(bookId: String, s: AppState = _state.value): BookChoice? =
        if (bookId in s.archived) BookChoice.Done else BookChoice.entries.firstOrNull { it.key == s.bookChoices[bookId] }

    /** Sets (or with null, clears) a book's choice. "Done with this" archives the book right away. */
    fun setChoice(bookId: String, choice: BookChoice?) = update { s ->
        s.copy(
            bookChoices = if (choice == null || choice == BookChoice.Done) s.bookChoices - bookId else s.bookChoices + (bookId to choice.key),
            archived = if (choice == BookChoice.Done) s.archived + bookId else s.archived - bookId,
            pendingArchive = s.pendingArchive - bookId,
        )
    }

    /** Moves swiped cards into the archive and drops a block in progress from an archived book. */
    private fun applyArchive() = update { s ->
        val archived = s.archived + s.pendingArchive
        val progressItem = s.inProgress?.let { item(it.itemId) }
        val dropProgress = progressItem != null && ((progressItem.bookId ?: progressItem.id) in archived)
        s.copy(archived = archived, pendingArchive = emptySet(), inProgress = if (dropProgress) null else s.inProgress)
    }

    fun recordShortfall(minutesLeft: Int) = update { s ->
        if (s.shortfalls.any { it.day == s.day && it.minutesLeft == minutesLeft }) s
        else s.copy(shortfalls = (s.shortfalls + Shortfall(s.day, minutesLeft, System.currentTimeMillis())).takeLast(500))
    }

    fun book(id: String?): BookPack? = _books.value.firstOrNull { it.id == id }

    fun item(id: String): ReadItem? {
        _posts.value.firstOrNull { it.id == id }?.let { return it.toItem() }
        for (book in _books.value) {
            val block = book.blocks.firstOrNull { it.id == id } ?: continue
            return block.toItem(book)
        }
        return null
    }

    fun nextBlock(item: ReadItem): Block? {
        val book = book(item.bookId) ?: return null
        return book.blocks.firstOrNull { it.order == (item.order ?: return null) + 1 }
    }

    fun Block.toItem(book: BookPack) = ReadItem(
        id = id, source = book.title, title = title, hook = hook, recap = recap,
        paragraphs = paragraphs, words = words,
        playlistPrompts = listOfNotNull(playlistPrompt.takeIf { it.isNotBlank() }?.let { "This passage" to it }) +
            book.playlistPrompts.map { (k, v) -> "The book · $k" to v },
        videos = (videos + book.videos).distinctBy { it.youtubeId },
        bookId = book.id, order = order, url = book.sourceUrl, language = book.language,
        characters = characters,
    )

    fun BlogPost.toItem() = ReadItem(
        id = id, source = feedTitle, title = title, hook = hook, recap = null,
        paragraphs = paragraphs, words = words,
        playlistPrompts = listOfNotNull(playlistPrompt.takeIf { it.isNotBlank() }?.let { "This post" to it }),
        videos = emptyList(), url = url,
    )
}
