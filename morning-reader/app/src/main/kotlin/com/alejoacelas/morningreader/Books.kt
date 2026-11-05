package com.alejoacelas.morningreader

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A pack the agent server has built, as listed by `GET /books/packs`. */
@Serializable
data class RemotePack(val id: String, val title: String = "", val author: String? = null, val bytes: Long, val updated: Long = 0)

/**
 * Books requested from the app. The agent server (tailnet only) finds each book and builds its
 * pack; every device downloads the packs it doesn't have yet. A request stays on the device until
 * the server accepts it, and background jobs send it, follow its status and fetch new packs.
 */
object Books {
    private const val TAG = "Books"
    private val base = BuildConfig.BOOKS_URL
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http: OkHttpClient = Ai.http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()
    private val packId = Regex("[A-Za-z0-9._-]+")

    /** True while a sync runs, for the Library's status line. */
    val syncing = MutableStateFlow(false)

    fun submit(context: Context, query: String) {
        val now = System.currentTimeMillis()
        val request = BookRequest(UUID.randomUUID().toString(), query.trim(), if (Eink.on) "boox" else "phone", created = now, updated = now)
        Telemetry.log("book_request", "id" to request.id, "query" to request.query)
        Store.update { it.copy(bookRequests = listOf(request) + it.bookRequests) }
        Store.saveNow()
        syncNow(context)
    }

    /** Sends waiting requests, refreshes statuses and fetches new packs now, or once there's network. */
    fun syncNow(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE, delayMinutes: Long = 0) {
        val work = OneTimeWorkRequestBuilder<BookWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("books", policy, work)
    }

    /** Checks for packs built for any device every two hours. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BookWorker>(2, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("books-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Syncs right away while the app is open. If the server can't be reached, or a book is still
     * being prepared, a background job carries on.
     */
    fun syncInApp(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val ok = sync()
            if (!ok || hasActive()) syncNow(app, ExistingWorkPolicy.KEEP, delayMinutes = if (ok) 2 else 0)
        }
    }

    fun hasActive(s: AppState = Store.state.value) = s.bookRequests.any { it.active }

    /** Sends, refreshes and downloads. Returns false if the server couldn't be reached. */
    suspend fun sync(): Boolean = lock.withLock {
        syncing.value = true
        try {
            val sent = sendWaiting()
            val ok = runCatching { refreshStatuses(); fetchPacks() }
                .onFailure { Log.w(TAG, "sync failed", it); Telemetry.log("book_sync_failed", "error" to it.message) }
                .isSuccess
            sent && ok
        } finally {
            syncing.value = false
        }
    }

    private suspend fun sendWaiting(): Boolean {
        for (request in Store.state.value.bookRequests.filter { !it.sent }.reversed()) {
            val accepted = runCatching { send(request) }.onFailure {
                Log.w(TAG, "sending failed", it); Telemetry.log("book_request_failed", "id" to request.id, "error" to it.message)
            }.getOrNull() ?: return false
            Store.update { s -> s.copy(bookRequests = mergeRequests(s.bookRequests, listOf(accepted))) }
            Store.saveNow()
        }
        return true
    }

    private suspend fun send(request: BookRequest): BookRequest = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("id", request.id); put("query", request.query); put("device", request.device)
        }
        val http = Request.Builder().url("$base/requests")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Books.http.newCall(http).execute().use { resp ->
            val text = resp.body.string()
            check(resp.isSuccessful) { "server said ${resp.code}: ${text.take(200)}" }
            Store.json.decodeFromString(BookRequest.serializer(), text)
        }
    }

    private suspend fun refreshStatuses() = withContext(Dispatchers.IO) {
        val remote = get("$base/requests") { Store.json.decodeFromString(ListSerializer(BookRequest.serializer()), it) }
        Store.update { s -> s.copy(bookRequests = mergeRequests(s.bookRequests, remote)) }
        Store.saveNow()
    }

    private suspend fun fetchPacks() = withContext(Dispatchers.IO) {
        val remote = get("$base/packs") { Store.json.decodeFromString(ListSerializer(RemotePack.serializer()), it) }
        val dir = Store.packsDir
        val local = dir.listFiles { f -> f.extension == "json" }.orEmpty().associate { it.nameWithoutExtension to it.length() }
        val wanted = packsToFetch(remote.filter { it.id.matches(packId) }, local)
        for (pack in wanted) {
            val tmp = File(dir, "${pack.id}.json.part")
            http.newCall(Request.Builder().url("$base/packs/${pack.id}.json").build()).execute().use { resp ->
                check(resp.isSuccessful) { "pack ${pack.id}: server said ${resp.code}" }
                tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
            }
            // Check it parses before it replaces anything, so a bad download never hides a book.
            Store.json.decodeFromString(BookPack.serializer(), tmp.readText())
            val bytes = tmp.length()
            check(tmp.renameTo(File(dir, "${pack.id}.json"))) { "couldn't move ${tmp.name} into place" }
            Telemetry.log("book_pack_downloaded", "pack" to pack.id, "bytes" to bytes)
        }
        if (wanted.isNotEmpty()) Store.reload()
    }

    private fun <T> get(url: String, parse: (String) -> T): T =
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val text = resp.body.string()
            check(resp.isSuccessful) { "server said ${resp.code} for $url" }
            parse(text)
        }
}

/** Adds the server's copies to the local list (server wins), newest first, keeping the last 30. */
fun mergeRequests(local: List<BookRequest>, remote: List<BookRequest>): List<BookRequest> {
    val byId = local.associateBy { it.id }.toMutableMap()
    remote.forEach { byId[it.id] = it.copy(sent = true) }
    return byId.values.sortedByDescending { it.created }.take(30)
}

/** Packs missing on the device or whose size differs from the server's copy. */
fun packsToFetch(remote: List<RemotePack>, localSizes: Map<String, Long>): List<RemotePack> =
    remote.filter { localSizes[it.id] != it.bytes }

class BookWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Store.reload()
        if (!Books.sync()) return Result.retry()
        // Follow a book being prepared until it's done; builds take a few minutes.
        if (Books.hasActive()) Books.syncNow(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE, delayMinutes = 2)
        return Result.success()
    }
}
