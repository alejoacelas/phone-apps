package com.alejoacelas.morningreader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
private fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
fun LibraryScreen(modifier: Modifier) {
    val nav = LocalNav.current
    val books by Store.books.collectAsState()
    val state by Store.state.collectAsState()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { ScreenTitle("Library") }
        if (books.isEmpty()) item { Text("No books yet. On the Mac, run ./pack starter.") }
        items(books, key = { it.id }) { book ->
            val read = book.blocks.count { it.id in state.finished }
            Column(Modifier.fillMaxWidth().clickable { nav.go(Screen.Book(book.id)) }) {
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                Text(listOfNotNull(book.author, book.year?.toString()).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(book.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$read of ${book.blocks.size} blocks read" + (Store.choiceFor(book.id, state)?.let { " · " + it.label.lowercase() } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f))
                    if (book.id in state.archived) TextButton(onClick = { Store.restore(book.id) }) { Text("Restore") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(bookId: String, modifier: Modifier) {
    val nav = LocalNav.current
    val book = Store.book(bookId) ?: run { Text("Book not found", Modifier.padding(24.dp)); return }
    val state by Store.state.collectAsState()
    var showMusic by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                if (!Eink.on && book.playlistPrompts.isNotEmpty()) IconButton(onClick = { showMusic = true }) {
                    Icon(Icons.Outlined.MusicNote, "Music for this book")
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(book.title, style = MaterialTheme.typography.headlineMedium)
                Text(listOfNotNull(book.author, book.year?.toString(), book.origin, book.edition.ifBlank { null })
                    .joinToString(" · "), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(book.summary, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                BookChoiceRow(book.id, state)
                if (!Eink.on && book.secondary.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("GO DEEPER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    book.secondary.forEach { s ->
                        SecondaryLink(s, dim = s.url in state.opened || s.youtubeId in state.watched)
                    }
                } else if (!Eink.on && book.videos.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("SHORT VIDEOS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    book.videos.forEach { v ->
                        TextButton(onClick = { nav.watch(v) }, modifier = Modifier.alpha(if (v.youtubeId in state.watched) 0.5f else 1f)) {
                            Column {
                                Text("${v.title} · ${(v.seconds + 59) / 60} min", style = MaterialTheme.typography.bodyMedium)
                                if (v.why.isNotBlank()) Text(v.why, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("BLOCKS · ENTRY POINTS MARKED ◆", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        items(book.blocks, key = { it.id }) { block ->
            val minutes = Store.minutesFor(block.words)
            val done = block.id in state.finished
            Column(Modifier.fillMaxWidth().clickable {
                with(Store) { nav.read(block.toItem(book), minutes) }
            }.padding(horizontal = 20.dp, vertical = 12.dp).alpha(if (done) 0.55f else 1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (block.entryPoint) "◆ " else "") + block.title, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    if (done) Icon(Icons.Outlined.CheckCircle, "Read", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (block.hook.isNotBlank()) Text(block.hook, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("About $minutes min", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    if (showMusic) ModalBottomSheet(onDismissRequest = { showMusic = false }) {
        MusicPrompts(book.playlistPrompts.map { (k, v) -> "The book · $k" to v })
    }
}

/** One row of the Notes tab: a note or an Ask, on a passage of a book. */
private class NoteEntry(val at: Long, val where: String, val selection: String, val label: String, val text: String,
                        val answer: String? = null)

/** Notes and Ask answers, newest first, each with its book, block and the text selected. Explains aren't shown. */
@Composable
fun NotesScreen(modifier: Modifier) {
    val state by Store.state.collectAsState()
    val notes by Store.notes.collectAsState()
    val entries = remember(state.highlights, notes) {
        (notes.map { NoteEntry(it.at, "${it.source} · ${it.blockTitle}", it.selection, "Note", it.note) } +
            state.highlights.mapNotNull { h ->
                h.askedQuestion?.let { NoteEntry(h.at, h.source, h.text, "Ask", it, h.plainAnswer) }
            }).sortedByDescending { it.at }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { ScreenTitle("Notes") }
        if (entries.isEmpty()) item {
            Text("Select text while reading and tap Note or Ask. Your notes and answers to your questions are kept here.",
                style = MaterialTheme.typography.bodyLarge)
        }
        items(entries, key = { it.label + it.at }) { e ->
            Column {
                Text(e.where.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text("“${e.selection}”", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
                Spacer(Modifier.height(6.dp))
                Text(if (e.answer != null) "Q: ${e.text}" else e.text, style = MaterialTheme.typography.titleMedium)
                e.answer?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier) {
    val nav = LocalNav.current
    val state by Store.state.collectAsState()
    val refreshing by Blogs.running.collectAsState()
    var newFeed by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val added = runCatching { Store.importOpml(uri) }.getOrElse { -1 }
            nav.say(if (added >= 0) "Imported $added new feeds." else "Couldn't read that file.")
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("Settings") }
        item {
            Text("DAILY LIMIT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { Store.update { it.copy(budgetMinutes = (it.budgetMinutes - 5).coerceAtLeast(5)) } }) { Text("−5") }
                Text("${state.budgetMinutes} minutes", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp))
                OutlinedButton(onClick = { Store.update { it.copy(budgetMinutes = (it.budgetMinutes + 5).coerceAtMost(240)) } }) { Text("+5") }
            }
            Text("A block starts only if it fits in what's left. The block you're reading can always be finished.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("READING SPEED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("${state.wordsPerMinute.toInt()} words per minute" +
                if (state.speedSamples == 0) " (starting estimate)" else ", learned from ${state.speedSamples} blocks",
                style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { Store.update { it.copy(wordsPerMinute = 238.0, speedSamples = 0) } }) { Text("Reset") }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("BLOG FEEDS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("${state.feeds.size} feeds. Posts shorter than 5 minutes are left out.", style = MaterialTheme.typography.bodyLarge)
            if (state.lastBlogRefresh > 0) Text(
                "Last checked ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.lastBlogRefresh))}. ${state.blogStatus}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import OPML") }
                OutlinedButton(enabled = !refreshing, onClick = { Blogs.refreshNow(nav.say) }) {
                    Text(if (refreshing) "Checking…" else "Check now")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newFeed, { newFeed = it }, label = { Text("Add feed URL") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = newFeed.startsWith("http"), onClick = {
                    val url = newFeed.trim()
                    Store.update { s -> if (s.feeds.any { it.url == url }) s else s.copy(feeds = s.feeds + Feed(url, Uri.parse(url).host ?: url)) }
                    newFeed = ""
                }) { Text("Add") }
            }
        }
        items(state.feeds, key = { it.url }) { feed ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(feed.title.ifBlank { feed.url }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { Store.update { s -> s.copy(feeds = s.feeds.filterNot { it.url == feed.url }) } }) { Text("Remove") }
            }
        }
        if (!Eink.on) item {
            Spacer(Modifier.height(16.dp))
            FeatureRequestBox()
        }
    }
}

/** A feature request, filed as a GitHub issue. Used at the bottom of Settings and from the reader. */
@Composable
fun FeatureRequestBox(onSent: () -> Unit = {}) {
    val nav = LocalNav.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by Store.state.collectAsState()
    var request by remember { mutableStateOf("") }
    Column {
        Text("REQUEST A FEATURE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(request, { request = it }, placeholder = { Text("What should the next version do?") },
            minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            val waiting = state.pendingRequests.size
            Text(
                when {
                    waiting > 0 && BuildConfig.GITHUB_ISSUES_TOKEN.isBlank() ->
                        "$waiting saved on the phone. This build has no GitHub token; they'll be filed after an install that has one."
                    waiting > 0 -> "$waiting waiting to be filed on GitHub. They'll go once the phone is online."
                    state.lastFiledIssue > 0 -> "Last request filed as issue #${state.lastFiledIssue}."
                    else -> "Filed as an issue on the app's GitHub repository."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = request.isNotBlank(), onClick = {
                Requests.submit(context, request)
                request = ""
                nav.say("Request saved. Filing it on GitHub…")
                onSent()
            }) { Text("Send") }
        }
    }
}
