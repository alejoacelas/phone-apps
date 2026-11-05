package com.alejoacelas.morningreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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

/** What the reader selected, with enough of the book around it for the model to answer. */
class LookupContext(
    val item: ReadItem,
    val selection: String,
    /** Index in `item.paragraphs` of the paragraph the selection starts in. */
    val paragraph: Int,
    /** The paragraph(s) the selection spans, with the selection wrapped in <mark>…</mark>. */
    val marked: String,
)

/** Direct Claude API: Opus by default; Haiku for latency-sensitive reading helpers. */
object Ai {
    const val MODEL = "claude-opus-5-5"
    const val FAST_MODEL = "claude-haiku-5-5"
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun request(body: JsonObject) = Request.Builder()
        .url("https://api.anthropic.com/v1/messages")
        .header("x-api-key", BuildConfig.ANTHROPIC_API_KEY)
        .header("anthropic-version", "2023-06-01")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    private fun body(model: String, system: List<Pair<String, Boolean>>, user: String, maxTokens: Int,
                     effort: String, stream: Boolean = false) = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        if (stream) put("stream", true)
        if (system.isNotEmpty()) put("system", buildJsonArray {
            // Each (text, cache) pair is a block; a cached block ends the prefix reused across lookups.
            system.forEach { (text, cache) ->
                add(buildJsonObject {
                    put("type", "text"); put("text", text)
                    if (cache) putJsonObject("cache_control") { put("type", "ephemeral"); put("ttl", "1h") }
                })
            }
        })
        putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", user) }) }
        putJsonObject("output_config") { put("effort", effort) }
        if (model == FAST_MODEL) putJsonObject("thinking") { put("type", "disabled") }
    }

    /** One-shot call for background jobs (feed hooks). */
    suspend fun ask(prompt: String, maxTokens: Int = 600, jsonReply: Boolean = false,
                    model: String = MODEL, effort: String = "low", kind: String = "other"): String =
        withContext(Dispatchers.IO) {
            val system = if (jsonReply) listOf("Return only valid JSON. No Markdown fences or commentary." to false) else emptyList()
            val started = System.currentTimeMillis()
            runCatching {
                http.newCall(request(body(model, system, prompt, maxTokens, effort))).execute().use { response ->
                    val text = response.body.string()
                    check(response.isSuccessful) { "Claude ${response.code}: ${text.take(200)}" }
                    val data = Store.json.parseToJsonElement(text).jsonObject
                    check(data["stop_reason"]?.jsonPrimitive?.content == "end_turn") { "Claude reply incomplete; try again." }
                    data["content"]!!.jsonArray
                        .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                        .joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }.trim()
                }
            }.also { result ->
                Telemetry.log("ai_call", "kind" to kind, "model" to model, "prompt" to prompt,
                    "answer" to result.getOrNull(), "error" to result.exceptionOrNull()?.message,
                    "ms" to System.currentTimeMillis() - started)
            }.getOrThrow()
        }

    /**
     * Streams a reply, calling [onText] with the text so far after each piece. Logs the full
     * request and reply, timings and cache use to telemetry.
     */
    private suspend fun stream(kind: String, model: String, system: List<Pair<String, Boolean>>, user: String,
                               maxTokens: Int, effort: String, extra: Array<Pair<String, Any?>>,
                               onText: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        var firstText = 0L
        val out = StringBuilder()
        var stop: String? = null
        val usage = mutableMapOf<String, Int>()
        val result = runCatching {
            http.newCall(request(body(model, system, user, maxTokens, effort, stream = true))).execute().use { response ->
                if (!response.isSuccessful) error("Claude ${response.code}: ${response.body.string().take(200)}")
                val source = response.body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val event = Store.json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
                    when (event["type"]?.jsonPrimitive?.content) {
                        "message_start" -> event["message"]?.jsonObject?.get("usage")?.jsonObject?.forEach { (k, v) ->
                            runCatching { usage[k] = v.jsonPrimitive.int }
                        }
                        "content_block_delta" -> {
                            val delta = event["delta"]!!.jsonObject
                            if (delta["type"]?.jsonPrimitive?.content == "text_delta") {
                                if (firstText == 0L) firstText = System.currentTimeMillis() - started
                                out.append(delta["text"]!!.jsonPrimitive.content)
                                withContext(Dispatchers.Main) { onText(out.toString().trim()) }
                            }
                        }
                        "message_delta" -> {
                            stop = event["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content
                            event["usage"]?.jsonObject?.get("output_tokens")?.let { usage["output_tokens"] = it.jsonPrimitive.int }
                        }
                        "error" -> error("Claude error: ${event["error"]}")
                    }
                }
            }
            check(stop == "end_turn") { "Claude reply incomplete ($stop); try again." }
            out.toString().trim()
        }
        Telemetry.log("ai_call", "kind" to kind, "model" to model, "effort" to effort,
            "system" to system.joinToString("\n\n") { it.first }, "prompt" to user,
            "answer" to out.toString().trim(), "stop" to stop, "error" to result.exceptionOrNull()?.message,
            "ms_first_text" to firstText, "ms" to System.currentTimeMillis() - started,
            "input_tokens" to usage["input_tokens"], "cache_read" to usage["cache_read_input_tokens"],
            "cache_write" to usage["cache_creation_input_tokens"], "output_tokens" to usage["output_tokens"], *extra)
        result.getOrThrow()
    }

    private const val ROLE =
        "You help someone reading a book or blog post on their phone or e-reader. They select text and ask " +
            "about it. You see the whole passage they are reading, and the one before it when there is one."

    /** The part of the prompt shared by every lookup in a block, cached so lookups start fast. */
    fun blockContext(item: ReadItem): String {
        val book = Store.book(item.bookId)
        val previous = book?.let { b -> item.order?.let { o -> b.blocks.firstOrNull { it.order == o - 1 } } }
        return buildString {
            if (book != null) {
                append("BOOK: ${book.title}")
                if (book.author.isNotBlank()) append(" by ${book.author}")
                book.year?.let { append(" ($it)") }
                append(". Language: ${book.language}.\n")
            } else append("BLOG POST from ${item.source}. Language: ${item.language ?: "as written"}.\n")
            append("PASSAGE BEING READ: ${item.title}\n")
            item.recap?.let { append("\nWHAT HAPPENED BEFORE THIS PASSAGE: $it\n") }
            if (item.characters.isNotEmpty()) {
                append("\nCHARACTERS SO FAR:\n")
                item.characters.forEach { append("- ${it.name}: ${it.note}\n") }
            }
            if (previous != null) {
                append("\n<previous_passage title=\"${previous.title}\">\n")
                append(previous.paragraphs.joinToString("\n\n"))
                append("\n</previous_passage>\n")
            }
            append("\n<passage title=\"${item.title}\">\n")
            item.paragraphs.forEachIndexed { i, p -> append("[${i + 1}] ").append(p).append("\n\n") }
            append("</passage>\n\nThe reader has read up to this passage. Never reveal what happens later in the book.")
        }
    }

    private fun system(item: ReadItem) = listOf(ROLE to false, blockContext(item) to true)

    private fun where(ctx: LookupContext) =
        "They selected this, in paragraph [${ctx.paragraph + 1}] of the passage:\n\"${ctx.selection}\"\n\n" +
            "That paragraph with the selection marked:\n${ctx.marked}"

    private fun language(ctx: LookupContext) = ctx.item.language ?: "the language of the passage"

    /** Warms the cache for this block, so the first lookup is as fast as later ones. */
    suspend fun prewarm(item: ReadItem) = withContext(Dispatchers.IO) {
        for (model in listOf(MODEL)) {
            val started = System.currentTimeMillis()
            val result = runCatching {
                http.newCall(request(body(model, system(item), "(warming the cache)", 0, "low"))).execute().use {
                    check(it.isSuccessful) { "Claude ${it.code}: ${it.body.string().take(200)}" }
                }
            }
            Telemetry.log("ai_prewarm", "model" to model, "item" to item.id, "ms" to System.currentTimeMillis() - started,
                "error" to result.exceptionOrNull()?.message)
        }
    }

    /** "I don't understand what this means": the meaning first, then context only when it helps. */
    suspend fun explain(ctx: LookupContext, onText: (String) -> Unit): String {
        val user = where(ctx) + "\n\n" + """
            They selected it because they don't understand what it means. Tell them, plainly and briefly:
            - Start with the meaning as used here. For a word or phrase, what it means in this sentence. For a longer
              selection, say what it is saying in plain modern words, untangling syntax, irony, old or foreign words,
              and who "he", "she" or "it" refers to.
            - Then, only if it would genuinely help this make sense, add a sentence of context: a custom, an
              allusion, a historical fact, a pun, why a character would say this. Skip it when the meaning is enough.
            - Don't summarise the story or interpret themes, and don't comment on the excerpt or the selection itself.
            Keep it under 80 words. Answer in ${language(ctx)}. Plain text, no preamble, no markdown.
        """.trimIndent()
        return stream("explain", MODEL, system(ctx.item), user, 4000, "low",
            arrayOf("item" to ctx.item.id, "selection" to ctx.selection), onText)
    }

    /** A typed question about a selection, with the same context Explain gets. */
    suspend fun answer(question: String, ctx: LookupContext, onText: (String) -> Unit): String {
        val user = where(ctx) + "\n\nTheir question: " + question + "\n\n" + """
            Answer in two to five sentences, in ${language(ctx)} unless the question is in another language, in which
            case use the question's language. Use what the book has revealed so far; don't give away later events.
            Plain text, no preamble, no markdown.
        """.trimIndent()
        return stream("ask", MODEL, system(ctx.item), user, 8000, "low",
            arrayOf("item" to ctx.item.id, "selection" to ctx.selection, "question" to question), onText)
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
        val reply = Store.json.parseToJsonElement(ask(prompt, maxTokens = 300, jsonReply = true, model = FAST_MODEL,
            kind = "post_hook")) as JsonObject
        return (reply["hook"]?.jsonPrimitive?.content ?: "") to (reply["playlist_prompt"]?.jsonPrimitive?.content ?: "")
    }
}
