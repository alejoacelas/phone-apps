package com.alejoacelas.morningreader

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reading pauses longer than this stop counting toward time used. */
private const val IDLE_MS = 150_000L

private data class Lookup(val text: String, val question: String? = null, val answer: String? = null, val error: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(itemId: String, modifier: Modifier) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val item = remember(itemId) { Store.item(itemId) }
    if (item == null) {
        Text("This block is no longer available.", Modifier.padding(24.dp)); return
    }
    val state by Store.state.collectAsState()
    // A resumed block reopens where it was left; any other block opens at the top.
    val startScroll = remember(itemId) { Store.state.value.inProgress?.takeIf { it.itemId == itemId }?.scroll ?: 0 }
    val scroll = remember(itemId) { ScrollState(startScroll) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var finished by remember(itemId) { mutableStateOf(itemId in Store.state.value.finished && Store.state.value.inProgress?.itemId != itemId) }
    var lookup by remember { mutableStateOf<Lookup?>(null) }
    var asking by remember { mutableStateOf<Selection?>(null) }
    var noting by remember { mutableStateOf<Selection?>(null) }
    var showMusic by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(scroll.value) { lastInteraction = System.currentTimeMillis() }
    // Leaving the app mid-block (say, to pick music in Spotify) keeps counting, up to a cap.
    DisposableEffect(itemId, lifecycle) {
        Store.returnedToBlock(itemId)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!finished) Store.leftBlock(itemId)
                Lifecycle.Event.ON_START -> Store.returnedToBlock(itemId)
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Count active reading time: one tick per second while visible and not idle.
    LaunchedEffect(itemId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            lastInteraction = System.currentTimeMillis()
            while (true) {
                delay(1000)
                if (finished || System.currentTimeMillis() - lastInteraction > IDLE_MS) continue
                Store.update { s ->
                    val progress = s.inProgress?.takeIf { it.itemId == itemId }
                    s.copy(
                        usedSecondsToday = s.usedSecondsToday + 1,
                        inProgress = progress?.copy(activeSeconds = progress.activeSeconds + 1, scroll = scroll.value),
                    )
                }
            }
        }
    }

    /** Explain (no question) or answer a typed question about a selection. Asks show in Notes; Explains are only kept. */
    fun lookUp(sel: Selection, question: String?) {
        lookup = Lookup(sel.text, question = question)
        scope.launch {
            lookup = runCatching {
                if (question == null) Ai.explain(sel.text, sel.context, item.source, item.language)
                else Ai.answer(question, sel.text, sel.context, item.source, item.language)
            }.fold(
                { answer ->
                    Store.update { s ->
                        s.copy(highlights = listOf(Highlight(sel.text, answer, item.source + " · " + item.title,
                            System.currentTimeMillis(), question)) + s.highlights)
                    }
                    Lookup(sel.text, question = question, answer = answer)
                },
                { Lookup(sel.text, question = question, error = it.message ?: "Lookup failed") },
            )
        }
    }

    fun finish() {
        Store.update { s ->
            val active = s.inProgress?.takeIf { it.itemId == itemId }?.activeSeconds ?: 0
            val sample = if (active >= 60) item.words / (active / 60.0) else 0.0
            val learn = sample in 80.0..900.0
            s.copy(
                finished = s.finished + itemId,
                inProgress = s.inProgress?.takeUnless { it.itemId == itemId },
                wordsPerMinute = if (learn) s.wordsPerMinute * 0.7 + sample * 0.3 else s.wordsPerMinute,
                speedSamples = s.speedSamples + if (learn) 1 else 0,
            )
        }
        finished = true
        scope.launch { delay(100); scroll.animateScrollTo(scroll.maxValue) }
    }

    val previousRead = item.order?.let { order ->
        Store.book(item.bookId)?.blocks?.firstOrNull { it.order == order - 1 }?.id in state.finished
    } ?: false
    val colors = MaterialTheme.colorScheme

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(item.source, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                maxLines = 1, modifier = Modifier.weight(1f))
            // A dialog over the text, so the scroll position and the block's timer carry on underneath.
            if (!Eink.on) IconButton(onClick = { requesting = true }) { Icon(Icons.Outlined.Feedback, "Request a feature") }
            if (!Eink.on && item.playlistPrompts.isNotEmpty()) IconButton(onClick = { showMusic = true }) {
                Icon(Icons.Outlined.MusicNote, "Music for this")
            }
            item.url?.takeIf { item.bookId == null }?.let { url ->
                IconButton(onClick = { openUrl(context, url) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open original") }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text(item.title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text("About ${Store.minutesFor(item.words)} min · ${item.words} words",
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                if (item.recap != null && !previousRead) {
                    Spacer(Modifier.height(16.dp))
                    Surface(color = colors.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("PREVIOUSLY", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(item.recap, style = MaterialTheme.typography.bodyMedium, color = colors.onSecondaryContainer)
                        }
                    }
                }
                if (item.characters.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(if (item.language == "Spanish") "QUIÉN ES QUIÉN" else "WHO'S WHO",
                        style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    item.characters.forEach { c ->
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(c.name) }
                                append(" — " + c.note)
                            },
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                BlockText(item, colors.onBackground.toArgb(), colors.primary.toArgb()) { action, sel ->
                    lastInteraction = System.currentTimeMillis()
                    when (action) {
                        SelectionAction.Explain -> lookUp(sel, null)
                        SelectionAction.Ask -> asking = sel
                        SelectionAction.Note -> noting = sel
                    }
                }
                Spacer(Modifier.height(28.dp))
                if (!finished) {
                    Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("I've finished this block") }
                } else {
                    AfterBlock(item, state)
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }

    lookup?.let { current ->
        ModalBottomSheet(onDismissRequest = { lookup = null }) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("“${current.text.take(300)}”", style = MaterialTheme.typography.titleMedium)
                current.question?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
                Spacer(Modifier.height(12.dp))
                when {
                    current.answer != null -> Text(current.answer, style = MaterialTheme.typography.bodyLarge)
                    current.error != null -> Text(current.error, color = colors.error)
                    else -> CircularProgressIndicator(Modifier.padding(8.dp))
                }
            }
        }
    }
    asking?.let { sel ->
        TextPrompt(title = "Ask about “${sel.text.take(60)}”", label = "Your question", singleLine = true,
            confirm = "Ask", onDismiss = { asking = null }) { question ->
            asking = null
            lookUp(sel, question)
        }
    }
    noting?.let { sel ->
        TextPrompt(title = "Note on “${sel.text.take(60)}”", label = "Note", singleLine = false,
            confirm = "Save", onDismiss = { noting = null }) { text ->
            noting = null
            Store.saveNote(Note(System.currentTimeMillis(), item.id, item.bookId, item.source, item.title,
                sel.paragraph, sel.start, sel.end, sel.text, text))
            Toast.makeText(context, "Note saved", Toast.LENGTH_SHORT).show()
        }
    }
    if (requesting) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { requesting = false }) {
            Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh) {
                Column(Modifier.padding(20.dp)) { FeatureRequestBox(onSent = { requesting = false }) }
            }
        }
    }
    if (showMusic) {
        ModalBottomSheet(onDismissRequest = { showMusic = false }) {
            MusicPrompts(item.playlistPrompts)
        }
    }
}

@Composable
private fun TextPrompt(title: String, label: String, singleLine: Boolean, confirm: String,
                       onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = singleLine,
                minLines = if (singleLine) 1 else 3, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Send else ImeAction.Default),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onConfirm(text.trim()) }))
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
fun MusicPrompts(prompts: List<Pair<String, String>>) {
    val context = LocalContext.current
    Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Music to read by", style = MaterialTheme.typography.titleLarge)
        Text("Tap a prompt to copy it and open Spotify, then paste it into AI Playlist.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        prompts.forEach { (label, prompt) ->
            OutlinedButton(onClick = { openSpotify(context, prompt) }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** What to do after finishing: the next block of this book, related videos, or back to today. */
@Composable
private fun AfterBlock(item: ReadItem, state: AppState) {
    val nav = LocalNav.current
    val next = Store.nextBlock(item)
    val book = Store.book(item.bookId)
    val left = Store.minutesLeft()
    Text("Done.", style = MaterialTheme.typography.titleLarge)
    Text("$left of ${state.budgetMinutes} minutes left today.", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (next != null && book != null) {
        val minutes = Store.minutesFor(next.words)
        val fits = Store.canStart(next.id, minutes)
        Spacer(Modifier.height(16.dp))
        Text("NEXT IN THIS BOOK · ABOUT $minutes MIN", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(next.title, style = MaterialTheme.typography.titleMedium)
        if (next.hook.isNotBlank()) Text(next.hook, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (fits) {
            Button(onClick = { with(Store) { nav.read(next.toItem(book), minutes, replace = true) } },
                modifier = Modifier.fillMaxWidth()) { Text("Read the next block") }
        } else {
            Text("That would take you past today's limit. It'll be here tomorrow.",
                style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(14.dp))
        Text("Next time, ${book.title}…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BookChoiceRow(book.id, state)
    }
    val further = book?.secondary.orEmpty().takeUnless { Eink.on }.orEmpty()
        .filter { it.url !in state.opened && (it.youtubeId == null || it.youtubeId !in state.watched) }.take(2)
    if (further.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Text("GO DEEPER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        further.forEach { SecondaryLink(it) }
    }
    // Packs built before trusted sources still carry videos from a broad search.
    val videos = if (!Eink.on && book?.secondary.isNullOrEmpty()) item.videos.filter { it.youtubeId !in state.watched }.take(2) else emptyList()
    if (videos.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Text("SHORT VIDEOS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        videos.forEach { v ->
            TextButton(onClick = { nav.watch(v) }) {
                Text("${v.title} · ${(v.seconds + 59) / 60} min", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = { nav.top(Screen.Today) }, modifier = Modifier.fillMaxWidth()) { Text("Back to today") }
}

/** One piece of secondary material: what it is, where it's from, how long, and why it's worth it. */
@Composable
fun SecondaryLink(item: Secondary, dim: Boolean = false) {
    val nav = LocalNav.current
    val kind = when (item.kind) {
        "video" -> "Video"; "audio" -> "Listen"; "essay" -> "Essay"; "book" -> "Book"; else -> item.kind
    }
    Column(Modifier.fillMaxWidth().clickable { nav.open(item) }.padding(vertical = 8.dp).alpha(if (dim) 0.5f else 1f)) {
        run {
            Text(listOfNotNull(kind, item.creator, item.minutes?.let { "$it min" }).joinToString(" · ").uppercase(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            if (item.why.isNotBlank()) Text(item.why, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Keep going / Maybe later / Done with this for a book. Optional: with no tap the book keeps its
 * place. Tapping the selected option again clears it.
 */
@Composable
fun BookChoiceRow(bookId: String, state: AppState) {
    val current = Store.choiceFor(bookId, state)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BookChoice.entries.forEach { choice ->
            FilterChip(
                selected = current == choice,
                onClick = { Store.setChoice(bookId, if (current == choice) null else choice) },
                label = { Text(choice.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
    current?.let {
        Text(it.effect, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A selection in a block, with the text around it to give the model context. */
data class Selection(val text: String, val start: Int, val end: Int, val paragraph: Int, val context: String)

/** The passage as a native TextView, so selecting text can offer Explain and the other actions. */
@Composable
private fun BlockText(item: ReadItem, textColor: Int, accent: Int, onAction: (SelectionAction, Selection) -> Unit) {
    val context = LocalContext.current
    val built = remember(item.id) { buildText(context.resources.getFont(R.font.literata), item.paragraphs) }
    // The text view outlives recompositions, so its menu handler must read the current block, not the first one.
    val currentBuilt by rememberUpdatedState(built)
    val currentAction by rememberUpdatedState(onAction)
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                typeface = ctx.resources.getFont(R.font.literata)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18.5f)
                setLineSpacing(0f, 1.12f)
                setTextIsSelectable(true)
                customSelectionActionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                        SelectionAction.entries.forEachIndexed { i, action ->
                            menu.add(Menu.NONE, action.menuId, i, action.label).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                        }
                        return true
                    }
                    // Android adds Share, Select all, Define, Translate and smart-selection items around our own;
                    // keep only Copy and ours. Runs on every prepare because Select all gets re-added each time.
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                        val keep = SelectionAction.entries.map { it.menuId }.toSet() + android.R.id.copy
                        var i = 0
                        while (i < menu.size()) {
                            val id = menu.getItem(i).itemId
                            if (id in keep) i++ else menu.removeItem(id)
                        }
                        return true
                    }
                    override fun onActionItemClicked(mode: ActionMode, menuItem: MenuItem): Boolean {
                        val action = SelectionAction.entries.firstOrNull { it.menuId == menuItem.itemId } ?: return false
                        currentBuilt.selection(selectionStart, selectionEnd)?.let { currentAction(action, it) }
                        mode.finish()
                        return true
                    }
                    override fun onDestroyActionMode(mode: ActionMode) {}
                }
            }
        },
        update = { tv ->
            tv.setTextColor(textColor)
            tv.highlightColor = (accent and 0x00FFFFFF) or 0x44000000
            if (tv.text !== built.text) tv.text = built.text
        },
    )
}

enum class SelectionAction(val label: String, val menuId: Int) {
    Explain("Explain", 0x5e1), Ask("Ask", 0x5e2), Note("Note", 0x5e3)
}

private class BuiltText(val text: CharSequence, val ranges: List<Pair<IntRange, String>>) {
    /** The selected text plus the paragraph it starts in and its neighbours. */
    fun selection(rawStart: Int, rawEnd: Int): Selection? {
        val start = minOf(rawStart, rawEnd).coerceIn(0, text.length)
        val end = maxOf(rawStart, rawEnd).coerceIn(start, text.length)
        val selected = text.substring(start, end).trim()
        if (selected.isEmpty()) return null
        val index = ranges.indexOfFirst { start in it.first }.takeIf { it >= 0 }
            ?: ranges.indexOfLast { it.first.first <= start }.coerceAtLeast(0)
        val around = (index - 1..index + 1).mapNotNull { ranges.getOrNull(it)?.second?.take(1500) }
        return Selection(selected, start, end, index, around.joinToString("\n\n"))
    }
}

private fun buildText(font: Typeface, paragraphs: List<String>): BuiltText {
    val out = SpannableStringBuilder()
    val ranges = mutableListOf<Pair<IntRange, String>>()
    val bold = Typeface.create(font, 600, false)
    paragraphs.forEachIndexed { i, raw ->
        if (i > 0) {
            out.append("\n")
            val gap = out.length
            out.append("\n")
            out.setSpan(RelativeSizeSpan(0.55f), gap, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val heading = raw.startsWith("## ")
        val text = if (heading) raw.removePrefix("## ") else raw
        val start = out.length
        out.append(text)
        if (heading) {
            out.setSpan(TypefaceSpan(bold), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(1.15f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        ranges += (start..out.length) to text
    }
    return BuiltText(out, ranges)
}
