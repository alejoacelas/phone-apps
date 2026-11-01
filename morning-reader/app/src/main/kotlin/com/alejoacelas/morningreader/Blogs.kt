package com.alejoacelas.morningreader

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fetches the user's feeds on the phone and keeps posts that take at least five minutes to read. */
object Blogs {
    private const val TAG = "Blogs"
    private const val MIN_MINUTES = 5
    private const val PER_FEED = 4
    private const val MAX_AGE_DAYS = 45L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BlogWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("blogs", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    val running = MutableStateFlow(false)

    /** True when the last refresh reached fewer than half the feeds (e.g. no network while asleep). */
    @Volatile var lastMostlyFailed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Refreshes outside any screen, so leaving the screen doesn't cancel it. */
    fun refreshNow(onDone: (String) -> Unit) {
        if (running.value) return
        running.value = true
        scope.launch {
            val status = runCatching { refresh() }.getOrElse { "Couldn't refresh posts: ${it.message}" }
            running.value = false
            withContext(Dispatchers.Main) { onDone(status) }
        }
    }

    private data class Entry(val title: String, val url: String, val published: Long, val html: String)

    suspend fun refresh(): String = withContext(Dispatchers.IO) {
        val state = Store.state.value
        val seen = state.seenPostUrls.toMutableSet()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_AGE_DAYS)
        val gate = Semaphore(4)
        val kept = AtomicInteger()
        val failed = AtomicInteger()
        coroutineScope {
            state.feeds.map { original ->
                async {
                    gate.withPermit {
                        val entries = runCatching { entries(original.url) }
                            .onFailure { Log.w(TAG, "feed ${original.url}", it); failed.incrementAndGet() }.getOrDefault(emptyList())
                        val feed = Store.state.value.feeds.firstOrNull { it.url == original.url } ?: original
                        entries.filter { it.published >= cutoff && it.url !in seen }.take(PER_FEED).forEach { entry ->
                            synchronized(seen) { seen += entry.url }
                            runCatching { consider(feed, entry) }
                                .onSuccess { if (it) kept.incrementAndGet() }
                                .onFailure { Log.w(TAG, "post ${entry.url}", it) }
                        }
                    }
                }
            }.awaitAll()
        }
        // Posts saved without a hook (for example when a refresh was interrupted) get one now.
        Store.posts.value.filter { it.hook.isBlank() }.forEach { post ->
            runCatching { Ai.postHook(post.title, post.feedTitle, post.paragraphs.joinToString("\n")) }
                .onSuccess { (hook, playlist) -> Store.savePost(post.copy(hook = hook, playlistPrompt = playlist)) }
        }
        val finished = Store.state.value.finished
        Store.prunePosts { it.published >= cutoff || it.id !in finished }
        lastMostlyFailed = state.feeds.isNotEmpty() && failed.get() * 2 > state.feeds.size
        val status = "Checked ${state.feeds.size} feeds: $kept new long posts" +
            (if (failed.get() > 0) ", ${failed.get()} feeds failed" else "")
        Store.update {
            it.copy(seenPostUrls = seen.toList().takeLast(3000), lastBlogRefresh = System.currentTimeMillis(),
                blogStatus = status)
        }
        status
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) morning-reader").build()
        Ai.http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return response.body.string()
        }
    }

    private fun entries(feedUrl: String): List<Entry> {
        val doc = Jsoup.parse(get(feedUrl), feedUrl, Parser.xmlParser())
        val name = (doc.selectFirst("channel > title") ?: doc.selectFirst("feed > title"))?.text()?.trim()
        if (!name.isNullOrEmpty()) Store.update { s ->
            s.copy(feeds = s.feeds.map { if (it.url == feedUrl && it.title != name) it.copy(title = name) else it })
        }
        val items = doc.select("item").ifEmpty { doc.select("entry") }
        return items.map { item ->
            val link = item.selectFirst("link[rel=alternate]")?.attr("href")
                ?: item.selectFirst("link[href]")?.attr("href")
                ?: item.selectFirst("link")?.text() ?: ""
            val html = item.getElementsByTag("content:encoded").firstOrNull()?.text()
                ?: item.selectFirst("content")?.text()
                ?: item.selectFirst("description, summary")?.text() ?: ""
            val date = item.selectFirst("pubDate, published, updated, dc|date")?.text()
                ?: item.getElementsByTag("dc:date").firstOrNull()?.text() ?: ""
            Entry(item.selectFirst("title")?.text() ?: "Untitled", link.trim(), parseDate(date), html)
        }.filter { it.url.startsWith("http") }.sortedByDescending { it.published }
    }

    private fun parseDate(text: String): Long = runCatching {
        ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.recoverCatching {
        OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli()
    }.getOrDefault(System.currentTimeMillis())

    /** Returns true if the post was long enough and saved. */
    private suspend fun consider(feed: Feed, entry: Entry): Boolean {
        var paragraphs = paragraphs(entry.html)
        var words = paragraphs.sumOf { wordCount(it) }
        if (words < 1500) { // feeds often carry only an excerpt; read the page itself
            runCatching {
                val article = Readability4J(entry.url, get(entry.url)).parse()
                val fromPage = paragraphs(article.content ?: "")
                val pageWords = fromPage.sumOf { wordCount(it) }
                if (pageWords > words) { paragraphs = fromPage; words = pageWords }
            }
        }
        if (Store.minutesFor(words) < MIN_MINUTES || words < MIN_MINUTES * 200) return false
        val (hook, playlist) = try {
            Ai.postHook(entry.title, feed.title, paragraphs.joinToString("\n"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "hook ${entry.url}", e); "" to ""
        }
        Store.savePost(
            BlogPost(
                id = "post-" + sha1(entry.url).take(16), feedTitle = feed.title, title = entry.title,
                url = entry.url, published = entry.published, hook = hook, playlistPrompt = playlist,
                words = words, paragraphs = paragraphs,
            )
        )
        return true
    }

    private val BLOCKS = setOf("p", "h1", "h2", "h3", "h4", "li", "blockquote", "pre")

    fun paragraphs(html: String): List<String> {
        val body = Jsoup.parse(html).body()
        body.select("script, style, figure, figcaption, nav, footer, form, iframe, .subscription-widget, " +
            ".button-wrapper, .footnote, [class*=share]").remove()
        val out = mutableListOf<String>()
        for (el in body.select(BLOCKS.joinToString(", "))) {
            if (el.parents().any { it.tagName() in BLOCKS }) continue
            if (el.tagName() == "blockquote" && el.selectFirst("p") != null) {
                el.select("p").forEach { p -> p.text().trim().takeIf { it.isNotEmpty() }?.let(out::add) }
                continue
            }
            val text = el.text().trim()
            if (text.isEmpty()) continue
            out += if (el.tagName().startsWith("h")) "## $text" else text
        }
        if (out.isEmpty()) body.text().trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    private fun wordCount(text: String) = text.split(Regex("\\s+")).count { it.isNotBlank() }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

class BlogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Store.reload()
        return runCatching { Blogs.refresh() }.fold(
            { if (Blogs.lastMostlyFailed) Result.retry() else Result.success() },
            { Result.retry() },
        )
    }
}
