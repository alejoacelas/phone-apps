package com.alejoacelas.morningreader

import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri

/** Copies a playlist prompt and opens Spotify, where it can be pasted into AI Playlist. */
fun openSpotify(context: Context, prompt: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Playlist prompt", prompt))
    Toast.makeText(context, "Prompt copied. In Spotify, open AI Playlist and paste it.", Toast.LENGTH_LONG).show()
    val intent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
        ?: Intent(Intent.ACTION_VIEW, "https://open.spotify.com".toUri())
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Opens a video in the YouTube app if it can take it, otherwise in a browser. Every place that
 * shows a video goes through here. Returns false (after telling the user) if nothing could open it.
 */
fun openVideo(context: Context, youtubeId: String): Boolean {
    val url = "https://www.youtube.com/watch?v=$youtubeId".toUri()
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW, url).setPackage("com.google.android.youtube"),
        Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE),
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).setData(url),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (e: ActivityNotFoundException) {
            Log.w("Actions", "can't open video with $intent", e)
        } catch (e: SecurityException) {
            Log.w("Actions", "can't open video with $intent", e)
        }
    }
    Toast.makeText(context, "No app on this phone can open $url", Toast.LENGTH_LONG).show()
    return false
}

/** Videos open in YouTube, radio episodes in an audio player (or the browser), everything else in the browser. */
fun openSecondary(context: Context, item: Secondary): Boolean {
    if (item.youtubeId != null) return openVideo(context, item.youtubeId)
    if (item.audioUrl != null) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.audioUrl.toUri(), "audio/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (e: ActivityNotFoundException) {
            Log.w("Actions", "no audio player for ${item.audioUrl}", e)
        }
    }
    return try {
        openUrl(context, item.url); true
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open ${item.url}", Toast.LENGTH_LONG).show(); false
    }
}

fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
