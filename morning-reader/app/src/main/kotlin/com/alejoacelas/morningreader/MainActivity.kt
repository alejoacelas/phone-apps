package com.alejoacelas.morningreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Today : Screen
    data object Library : Screen
    data object Notes : Screen
    data object Settings : Screen
    data class Book(val id: String) : Screen
    data class Reader(val itemId: String) : Screen
}

/** Navigation plus the two actions every screen needs: start a reading block, watch a video. */
class Nav(private val stack: MutableList<Screen>, val say: (String) -> Unit, private val context: android.content.Context) {
    val current get() = stack.last()
    fun go(screen: Screen) { stack += screen }
    fun top(screen: Screen) { stack.clear(); stack += screen }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    /** Opens a block if it fits in today's budget (or is the one in progress). */
    fun read(item: ReadItem, minutes: Int = Store.minutesFor(item.words), replace: Boolean = false) {
        if (!Store.canStart(item.id, minutes)) {
            say(overBudget(minutes)); return
        }
        Store.update { s ->
            if (s.inProgress?.itemId == item.id) s else s.copy(inProgress = InProgress(item.id))
        }
        if (replace) back()
        go(Screen.Reader(item.id))
    }

    fun watch(video: Video) {
        val minutes = maxOf(1, (video.seconds + 59) / 60)
        if (!Store.canStart("video-" + video.youtubeId, minutes)) {
            say(overBudget(minutes)); return
        }
        // Charge the budget only once the video actually opened.
        if (openVideo(context, video.youtubeId)) {
            Store.update { it.copy(usedSecondsToday = it.usedSecondsToday + video.seconds, watched = it.watched + video.youtubeId) }
        }
    }

    /**
     * Opens secondary material. Short videos are blocks and go through [watch]; longer videos,
     * radio, essays and books open outside the session and don't count toward the budget.
     */
    fun open(item: Secondary) {
        item.asBlockVideo?.let { watch(it); return }
        if (openSecondary(context, item)) Store.update { it.copy(opened = it.opened + item.url) }
    }

    private fun overBudget(minutes: Int): String {
        val left = Store.minutesLeft()
        return "That's about $minutes min and you have $left left today. Pick something shorter, or come back tomorrow."
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ReaderTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) Eink.homePresses.value++
    }

    override fun onStop() {
        super.onStop()
        Store.writeExports()
    }

    override fun onResume() {
        super.onResume()
        Store.reloadIfChanged()
        Requests.retryLater(this)
        // Mornings shouldn't depend on the background schedule: refresh stale or failed feeds on open.
        val last = Store.state.value.lastBlogRefresh
        if (Blogs.lastMostlyFailed || System.currentTimeMillis() - last > 6 * 60 * 60 * 1000L) Blogs.refreshNow { }
    }
}

@Composable
private fun AppRoot() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Today) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val nav = remember { Nav(stack, { msg -> scope.launch { snackbar.showSnackbar(msg) } }, context) }
    // As the Boox home screen, Back on Today does nothing instead of closing and redrawing the app.
    BackHandler(enabled = stack.size > 1 || Eink.on) { nav.back() }
    val homePresses by Eink.homePresses.collectAsState()
    LaunchedEffect(homePresses) { if (homePresses > 0) nav.top(Screen.Today) }
    val current = stack.last()
    val topLevel = current in listOf(Screen.Today, Screen.Library, Screen.Notes, Screen.Settings)

    CompositionLocalProvider(LocalNav provides nav) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (topLevel) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab(current, Screen.Today, "Today", Icons.Outlined.WbSunny)
                    Tab(current, Screen.Library, "Library", Icons.Outlined.AutoStories)
                    Tab(current, Screen.Notes, "Notes", Icons.Outlined.EditNote)
                    Tab(current, Screen.Settings, "Settings", Icons.Outlined.Settings)
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (current) {
                Screen.Today -> TodayScreen(modifier)
                Screen.Library -> LibraryScreen(modifier)
                Screen.Notes -> NotesScreen(modifier)
                Screen.Settings -> SettingsScreen(modifier)
                is Screen.Book -> BookScreen(current.id, modifier)
                // Keyed so each block gets fresh state (scroll position, text view, selection handler).
                is Screen.Reader -> key(current.itemId) { ReaderScreen(current.itemId, modifier) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(current: Screen, screen: Screen, label: String, icon: ImageVector) {
    val nav = LocalNav.current
    NavigationBarItem(
        selected = current == screen, onClick = { nav.top(screen) },
        icon = { Icon(icon, contentDescription = null) }, label = { Text(label) },
    )
}
