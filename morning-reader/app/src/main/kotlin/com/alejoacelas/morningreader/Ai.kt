package com.alejoacelas.morningreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Direct Claude API: Opus by default; Haiku for latency-sensitive reading helpers. */
object Ai {
    private const val MODEL = "claude-opus-5-5"
    private const val FAST_MODEL = "claude-haiku-5-5"
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun ask(prompt: String, maxTokens: Int = 600, jsonReply: Boolean = false,
                    model: String = MODEL, effort: String = "low"): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    add(buildJsonObject { put("role", "user"); put("content", prompt) })
                }
                putJsonObject("output_config") { put("effort", effort) }
                if (model == FAST_MODEL) putJsonObject("thinking") { put("type", "disabled") }
                put("max_tokens", maxTokens)
                if (jsonReply) put("system", "Return only valid JSON. No Markdown fences or commentary.")
            }
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", BuildConfig.ANTHROPIC_API_KEY)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                check(response.isSuccessful) { "Claude ${response.code}: ${text.take(200)}" }
                val data = Store.json.parseToJsonElement(text).jsonObject
                check(data["stop_reason"]?.jsonPrimitive?.content == "end_turn") {
                    "Claude reply incomplete; try again."
                }
                data["content"]!!.jsonArray
                    .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                    .joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }.trim()
            }
        }

    /**
     * Explain is for an unknown word or a sentence that eludes the reader: give the meaning, briefly,
     * and mention a real event, person or work of the time only when the passage clearly refers to one.
     */
    suspend fun explain(selection: String, paragraph: String, source: String, language: String?): String {
        val words = selection.trim().split(Regex("\\s+")).size
        val task = if (words <= 2)
            "Say what the selected word or phrase means as it is used here, in one or two short sentences. " +
                "If it is old-fashioned, foreign or a period term, say so in a few words."
        else
            "Say plainly what the selected passage means: paraphrase it, untangling any difficult syntax, " +
                "irony or unfamiliar word, in one to three short sentences. Don't summarise the story or " +
                "interpret its place in the book. Only if the passage clearly refers to a real event, person, " +
                "custom or other work, add one short sentence naming it."
        val prompt = """
            Someone is reading "$source" and selected: "$selection"

            The text around it (the paragraph it is in, with the paragraphs before and after):
            $paragraph

            $task Answer in ${language ?: "the language of the paragraph"}. Plain text, no preamble, no markdown.
        """.trimIndent()
        return ask(prompt, maxTokens = 400, model = FAST_MODEL)
    }

    /** A typed question about a selection, with the same context Explain gets. */
    suspend fun answer(question: String, selection: String, paragraph: String, source: String, language: String?): String {
        val prompt = """
            Someone is reading "$source" and selected: "$selection"

            The text around it (the paragraph it is in, with the paragraphs before and after):
            $paragraph

            Their question about it: $question

            Answer in two to five sentences, in ${language ?: "the language of the paragraph"} unless the
            question is in another language, in which case use the question's language. Use only what
            the text so far reveals; don't give away later events. Plain text, no preamble, no markdown.
        """.trimIndent()
        // Low reasoning on Opus spends part of max_tokens thinking, so leave room for it.
        return ask(prompt, maxTokens = 2000, model = MODEL, effort = "low")
    }

    /** A hook line and a Spotify AI Playlist prompt for a blog post. */
    suspend fun postHook(title: String, feed: String, text: String): Pair<String, String> {
        val prompt = """
            A blog post from "$feed" titled "$title". Write, in the post's language:
            - "hook": one line, at most 20 words, saying concretely what the post argues or shows. Plain words, no hype.
            - "playlist_prompt": a Spotify AI Playlist prompt under 25 words matched to the post's mood and subject.
            Reply with JSON only: {"hook": "", "playlist_prompt": ""}

            POST:
            ${text.take(12000)}
        """.trimIndent()
        val reply = Store.json.parseToJsonElement(ask(prompt, maxTokens = 300, jsonReply = true, model = FAST_MODEL)) as JsonObject
        return (reply["hook"]?.jsonPrimitive?.content ?: "") to (reply["playlist_prompt"]?.jsonPrimitive?.content ?: "")
    }
}
