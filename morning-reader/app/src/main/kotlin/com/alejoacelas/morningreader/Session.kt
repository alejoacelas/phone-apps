package com.alejoacelas.morningreader

import kotlin.math.ceil

/** One option on today's screen. */
sealed interface Card {
    val key: String
    val minutes: Int

    data class Reading(val item: ReadItem, val label: String, override val minutes: Int) : Card {
        override val key get() = item.id
    }

    data class Watch(val video: Video, val book: BookPack, override val minutes: Int) : Card {
        override val key get() = "video-" + video.youtubeId
    }
}

/** Builds today's mix: what's in progress, then books and posts interleaved, with a video or two. */
object Session {
    fun cards(state: AppState, allBooks: List<BookPack>, allPosts: List<BlogPost>): List<Card> = with(Store) {
        val books = allBooks.filter { it.id !in state.archived }
            .map { b -> b.copy(videos = b.videos.filter { "video-" + it.youtubeId !in state.archived }) }
        val posts = allPosts.filter { it.id !in state.archived }
        val daySeed = state.day.hashCode()
        val cards = mutableListOf<Card>()
        state.inProgress?.let { progress ->
            item(progress.itemId)?.let { cards += Card.Reading(it, "Resume", minutesLeft(it, progress)) }
        }
        val taken = cards.map { it.key }.toMutableSet()
        val busyBook = state.inProgress?.let { item(it.itemId)?.bookId }

        val bookCards = books.filter { it.id != busyBook }.mapNotNull { book ->
            val lastRead = book.blocks.filter { it.id in state.finished }.maxByOrNull { it.order }
            val next = lastRead?.let { last -> book.blocks.firstOrNull { it.order > last.order && it.id !in state.finished } }
            val block: Block
            val label: String
            if (next != null) {
                block = next; label = "Continue"
            } else {
                // Rotate among the earliest unread entry points so a dip-in never lands on the ending.
                val entries = book.blocks.filter { it.entryPoint && it.id !in state.finished }.take(3)
                if (entries.isEmpty()) return@mapNotNull null
                block = entries[Math.floorMod(daySeed + book.id.hashCode(), entries.size)]
                label = if (block.order == 0) "Start" else "Dip in"
            }
            if (block.id in taken) null else Card.Reading(block.toItem(book), label, minutesFor(block.words))
        }.shuffledBy(daySeed)
            // "Keep going" books come first and "maybe later" ones last; books with no choice keep the daily shuffle.
            .sortedBy { card -> rank(state.bookChoices[card.item.bookId]) }

        val postCards = posts.filter { it.id !in state.finished && it.id !in taken }.take(4)
            .map { Card.Reading(it.toItem(), "New post", minutesFor(it.words)) }

        val videos = books.flatMap { b -> b.videos.map { it to b } }
            .filter { (v, _) -> v.youtubeId !in state.watched }
            .shuffledBy(daySeed).take(2)
            .map { (v, b) -> Card.Watch(v, b, maxOf(1, ceil(v.seconds / 60.0).toInt())) }

        val mixed = mutableListOf<Card>()
        val bookIt = bookCards.iterator()
        val postIt = postCards.iterator()
        while (bookIt.hasNext() || postIt.hasNext()) {
            if (bookIt.hasNext()) mixed += bookIt.next()
            if (postIt.hasNext()) mixed += postIt.next()
        }
        videos.forEachIndexed { i, v -> mixed.add(minOf(mixed.size, 2 + i * 4), v) }
        val offered = fillers(state, books, posts, (cards + mixed).map { it.key }.toSet(), mixed) + mixed
        // Near the end of the budget, put what still fits first.
        val left = minutesLeft()
        cards + if (left <= 15) offered.sortedBy { it.minutes > left } else offered
    }

    /**
     * When little time is left and the usual mix has nothing that fits, offer up to three
     * self-contained pieces that do: short entry points, whole poems or stories, posts, videos.
     */
    private fun fillers(state: AppState, books: List<BookPack>, posts: List<BlogPost>, taken: Set<String>,
                        mixed: List<Card>): List<Card> = with(Store) {
        val left = minutesLeft()
        if (left !in 1..15 || mixed.any { it is Card.Reading && it.minutes <= left }) return emptyList()
        val candidates = mutableListOf<Card>()
        books.forEach { book ->
            book.blocks.filter { (it.entryPoint || it.standalone) && it.id !in state.finished }
                .forEach { b -> candidates += Card.Reading(b.toItem(book), "Fits in $left min", minutesFor(b.words)) }
        }
        posts.filter { it.id !in state.finished }
            .forEach { candidates += Card.Reading(it.toItem(), "Fits in $left min", minutesFor(it.words)) }
        books.forEach { b ->
            b.videos.filter { it.youtubeId !in state.watched }
                .forEach { v -> candidates += Card.Watch(v, b, maxOf(1, ceil(v.seconds / 60.0).toInt())) }
        }
        // Prefer reading over videos, then the longest pieces that still fit, one per book or feed.
        candidates.filter { it.minutes <= left && it.key !in taken }
            .sortedWith(compareBy<Card> { it is Card.Watch }.thenByDescending { it.minutes })
            .distinctBy { if (it is Card.Reading) it.item.source else it.key }
            .take(3)
    }

    fun minutesLeft(item: ReadItem, progress: InProgress): Int {
        val total = Store.minutesFor(item.words)
        return maxOf(1, total - ((progress.activeSeconds + progress.awaySeconds) / 60).toInt())
    }

    private fun rank(choice: String?): Int = when (choice) {
        BookChoice.Keep.key -> 0
        BookChoice.Later.key -> 2
        else -> 1
    }

    private fun <T> List<T>.shuffledBy(seed: Int): List<T> = shuffled(java.util.Random(seed.toLong()))
}
