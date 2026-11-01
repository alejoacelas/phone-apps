package com.alejoacelas.morningreader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(modifier: Modifier) {
    val nav = LocalNav.current
    val state by Store.state.collectAsState()
    val books by Store.books.collectAsState()
    val posts by Store.posts.collectAsState()
    val loaded by Store.loaded.collectAsState()
    val cards = remember(state.day, state.finished, state.watched, state.inProgress?.itemId, books, posts, state.wordsPerMinute,
        state.usedSecondsToday / 60, state.budgetMinutes, state.archived) {
        Session.cards(state, books, posts).filterNot { Eink.on && it is Card.Watch }
    }
    val refreshing by Blogs.running.collectAsState()
    val used = (state.usedSecondsToday / 60).toInt()
    val left = Store.minutesLeft()
    // Record each time there are 5-10 minutes left and nothing on offer fits them.
    LaunchedEffect(cards, left) {
        if (loaded && left in 5..10 && cards.none { it is Card.Reading && it.minutes <= left && it.key != state.inProgress?.itemId }) {
            Store.recordShortfall(left)
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("This morning", style = MaterialTheme.typography.headlineMedium)
                }
                if (Eink.on) IconButton(onClick = { Eink.openBooxHome(context) }) {
                    Icon(Icons.Outlined.Apps, contentDescription = "Boox home")
                }
                IconButton(onClick = {
                    scope.launch { Store.reload() }
                    Blogs.refreshNow(nav.say)
                }) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (left > 0) "$left of ${state.budgetMinutes} minutes left" else "Today's ${state.budgetMinutes} minutes are used",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (used.toFloat() / state.budgetMinutes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {},
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            if (left == 0) {
                Spacer(Modifier.height(10.dp))
                Text("You can still finish what you've started. New blocks open tomorrow.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (loaded && cards.isEmpty()) item {
            Text(
                if (books.isEmpty()) "No books yet. On the Mac, run ./pack starter to add the first shelf."
                else "Nothing new to read. Add books with ./pack add, or refresh posts.",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 24.dp),
            )
        }
        items(cards, key = { it.key }) { card ->
            val archiving = Store.archiveKey(card) in state.pendingArchive
            SwipeToArchive(archiving, onSwipe = { Store.toggleArchive(Store.archiveKey(card)) }) {
                SessionCard(card, fits = Store.canStart(card.key, card.minutes), archiving = archiving)
            }
        }
    }
}

/** Swiping right to left greys a card out (or back in); the archive happens at the next reload. */
@Composable
private fun SwipeToArchive(archiving: Boolean, onSwipe: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onSwipe()
            scope.launch { state.reset() } // spring back; the card stays until the next reload
        },
        backgroundContent = {
            // Only while swiping: dimmed cards are translucent and would show the label at rest.
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(Modifier.fillMaxSize().padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Text(if (archiving) "Keep" else "Archive", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
        },
    ) { content() }
}

@Composable
fun SessionCard(card: Card, fits: Boolean, archiving: Boolean = false) {
    val nav = LocalNav.current
    val (label, title, hook, source) = when (card) {
        is Card.Reading -> listOf(card.label, card.item.title, card.item.hook, card.item.source)
        is Card.Watch -> listOf("Watch", card.video.title, card.video.why, "${card.video.channel} · ${card.book.title}")
    }
    Card(
        onClick = {
            when (card) {
                is Card.Reading -> nav.read(card.item, card.minutes)
                is Card.Watch -> nav.watch(card.video)
            }
        },
        modifier = Modifier.fillMaxWidth().alpha(if (archiving) 0.25f else if (fits) 1f else 0.45f),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = if (Eink.on) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card is Card.Watch) {
                    Icon(Icons.Outlined.PlayCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(6.dp))
                }
                Text("${label.uppercase()} · $source", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (hook.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(hook, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "About ${card.minutes} min" + when {
                    archiving -> " · archived at the next refresh (swipe again to keep)"
                    !fits -> " · past today's limit"
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("unused")
private fun Modifier.clickableIf(enabled: Boolean, onClick: () -> Unit) = if (enabled) clickable(onClick = onClick) else this
