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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Feature requests typed in Settings, filed as GitHub issues on the app's repository. A request
 * stays on the phone until GitHub accepts it, and a background job retries once there's network.
 */
object Requests {
    private const val TAG = "Requests"
    private const val REPO = "alejoacelas/phone-apps"
    private val lock = Mutex()

    fun submit(context: Context, text: String) {
        Store.update { it.copy(pendingRequests = it.pendingRequests + FeatureRequest(text.trim(), System.currentTimeMillis())) }
        Store.saveNow()
        retryLater(context)
    }

    /** Files waiting requests now if there's network, or as soon as there is. */
    fun retryLater(context: Context) {
        if (Store.state.value.pendingRequests.isEmpty()) return
        val work = OneTimeWorkRequestBuilder<RequestWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("feature-requests", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    /** Files every waiting request in order. Returns false if any is still waiting. */
    suspend fun fileWaiting(): Boolean = lock.withLock {
        for (request in Store.state.value.pendingRequests) {
            val number = runCatching { file(request) }.onFailure { Log.w(TAG, "filing failed", it) }.getOrNull()
                ?: return false
            Store.update { s -> s.copy(pendingRequests = s.pendingRequests - request, lastFiledIssue = number) }
            Store.saveNow()
        }
        true
    }

    private suspend fun file(request: FeatureRequest): Int = withContext(Dispatchers.IO) {
        check(BuildConfig.GITHUB_ISSUES_TOKEN.isNotBlank()) { "no GitHub token in this build" }
        val firstLine = request.text.lineSequence().first().trim()
        val title = if (firstLine.length <= 80) firstLine else firstLine.take(77).substringBeforeLast(' ') + "…"
        val day = java.time.Instant.ofEpochMilli(request.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val body = buildJsonObject {
            put("title", title)
            put("body", "${request.text}\n\n_Requested from the app on the phone, $day._")
        }
        val http = Request.Builder()
            .url("https://api.github.com/repos/$REPO/issues")
            .header("Authorization", "Bearer ${BuildConfig.GITHUB_ISSUES_TOKEN}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Ai.http.newCall(http).execute().use { resp ->
            val text = resp.body.string()
            check(resp.isSuccessful) { "GitHub said ${resp.code}: ${text.take(200)}" }
            Store.json.parseToJsonElement(text).jsonObject["number"]!!.jsonPrimitive.int
        }
    }
}

class RequestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Store.reload()
        return if (Requests.fileWaiting()) Result.success() else Result.retry()
    }
}
