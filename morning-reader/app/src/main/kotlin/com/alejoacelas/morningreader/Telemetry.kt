package com.alejoacelas.morningreader

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Everything the app does, sent to the telemetry service on the agent server (tailnet only).
 * Events are appended to a file on the device and uploaded in batches whenever the server is
 * reachable, so nothing is lost while offline or off Tailscale.
 */
object Telemetry {
    private const val TAG = "Telemetry"
    private lateinit var appContext: Context
    private val device get() = if (Eink.on) "boox" else "phone"
    /** One id per app process, to group a morning's events. */
    private val session = UUID.randomUUID().toString().take(8)
    private val dir get() = File(appContext.filesDir, "telemetry").apply { mkdirs() }
    private val pending get() = File(dir, "pending.jsonl")
    private val sending get() = File(dir, "sending.jsonl")

    fun init(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { log("crash", "thread" to thread.name, "error" to error.stackTraceToString()) }
            previous?.uncaughtException(thread, error)
        }
        log("app_start", "build" to BuildConfig.BUILD_TIME, "sdk" to android.os.Build.VERSION.SDK_INT,
            "model" to android.os.Build.MODEL)
    }

    /** Records one event. Values may be strings, numbers, booleans, lists or null. */
    fun log(type: String, vararg props: Pair<String, Any?>) {
        if (!::appContext.isInitialized) return
        val event = buildJsonObject {
            put("ts", System.currentTimeMillis())
            put("device", device)
            put("session", session)
            put("type", type)
            props.forEach { (k, v) -> put(k, toJson(v)) }
        }
        synchronized(this) { pending.appendText(event.toString() + "\n") }
        flushSoon()
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Collection<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }

    /** Uploads within a few seconds, batching whatever else is logged meanwhile. */
    fun flushSoon() {
        val work = OneTimeWorkRequestBuilder<TelemetryWorker>()
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork("telemetry", ExistingWorkPolicy.KEEP, work)
    }

    /** Sends everything waiting. Returns false if the server couldn't be reached. */
    suspend fun upload(): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@Telemetry) {
            if (!sending.exists() && pending.exists()) pending.renameTo(sending)
        }
        if (!sending.exists()) return@withContext true
        val ok = runCatching {
            val request = Request.Builder().url(BuildConfig.TELEMETRY_URL)
                .post(sending.readBytes().toRequestBody("application/x-ndjson".toMediaType())).build()
            Ai.http.newCall(request).execute().use { it.isSuccessful }
        }.onFailure { Log.w(TAG, "upload failed", it) }.getOrDefault(false)
        if (ok) sending.delete()
        // Events logged during the upload wait in `pending` for the next run.
        ok && !pending.exists()
    }
}

class TelemetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (Telemetry.upload()) Result.success() else Result.retry()
}
