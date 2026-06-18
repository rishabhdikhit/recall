package app.recall

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Analysis(
    val title: String,
    val summary: String,
    val transcript: String,
    val topic: String,
    val subtags: List<String>,
    val language: String,
    val hasSpeech: Boolean,
)

object Gemini {
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    // (label, model id). Curated, stable IDs — no custom field, so a renamed model needs an app update.
    val MODELS = listOf(
        "Gemini 3.5 Flash" to "gemini-3.5-flash",
        "Gemini 3.1 Pro" to "gemini-3.1-pro",
        "Gemini 3.1 Flash" to "gemini-3.1-flash",
        "Gemini 3.1 Flash-Lite" to "gemini-3.1-flash-lite",
        "Gemini 2.5 Flash" to "gemini-2.5-flash",
        "Gemini 2.5 Flash-Lite" to "gemini-2.5-flash-lite",
    )

    val TOPICS = listOf(
        "Tech", "Programming", "AI & ML", "Business", "Finance",
        "Marketing", "Productivity", "Health", "Fitness", "Food & Cooking",
        "Travel", "Education", "Science", "News & Politics", "Entertainment",
        "Music", "Art & Design", "Fashion & Beauty", "Sports", "Relationships",
        "Psychology", "DIY & Crafts", "Nature & Animals", "Gaming", "Other",
    )

    private const val PROMPT =
        "You are analyzing a short video (its audio). Produce a structured record of its CONTENT " +
        "(what is actually said), not metadata.\n" +
        "- title: a short, catchy, specific title (max 70 chars).\n" +
        "- summary: the core information as tight prose, 3-6 sentences.\n" +
        "- transcript: a clean transcript of the spoken words. Empty string if no speech.\n" +
        "- topic: the single best-fit topic from the allowed list.\n" +
        "- subtags: 2-5 specific lowercase keywords.\n" +
        "- language: the primary spoken language (e.g. English, Hindi). 'none' if no speech.\n" +
        "- hasSpeech: true if anyone speaks, false if music/visual only."

    private val client = OkHttpClient.Builder()
        .callTimeout(240, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun schema(): JSONObject {
        val props = JSONObject()
            .put("title", JSONObject().put("type", "STRING"))
            .put("summary", JSONObject().put("type", "STRING"))
            .put("transcript", JSONObject().put("type", "STRING"))
            .put("topic", JSONObject().put("type", "STRING").put("enum", JSONArray(TOPICS)))
            .put("subtags", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING")))
            .put("language", JSONObject().put("type", "STRING"))
            .put("hasSpeech", JSONObject().put("type", "BOOLEAN"))
        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", props)
            .put(
                "required",
                JSONArray(listOf("title", "summary", "transcript", "topic", "subtags", "language", "hasSpeech")),
            )
    }

    // Gemini's servers return 503/UNAVAILABLE when the model is overloaded, and 429 when
    // rate-limited. Both are transient — retry with backoff so the user just sees it succeed.
    private val BACKOFF_MS = longArrayOf(2_000, 5_000, 12_000, 25_000)

    private const val IMAGE_PROMPT =
        "You are analyzing screenshot image(s) — possibly several slides of one Instagram post/carousel. " +
        "Read ALL text in them (OCR) and understand the visuals, then produce a structured record.\n" +
        "- title: a short, catchy, specific title (max 70 chars).\n" +
        "- summary: the core information across all slides, as tight prose, 3-6 sentences.\n" +
        "- transcript: ALL the readable text from the images, kept useful and in order. Empty if there's no text.\n" +
        "- topic: the single best-fit topic from the allowed list.\n" +
        "- subtags: 2-5 specific lowercase keywords.\n" +
        "- language: the primary language of the text (e.g. English, Hindi). 'none' if no text.\n" +
        "- hasSpeech: always false for images."

    private fun captioned(base: String, caption: String): String =
        if (caption.isNotBlank()) {
            "$base\n\nThe author's caption (use as context — it often holds the key info, links or steps):\n$caption"
        } else {
            base
        }

    private fun run(apiKey: String, model: String, mediaParts: List<JSONObject>, promptText: String): Analysis {
        val parts = JSONArray()
        mediaParts.forEach { parts.put(it) }
        parts.put(JSONObject().put("text", promptText))
        val contents = JSONArray().put(JSONObject().put("parts", parts))
        val genConfig = JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", schema())
            .put("temperature", 0.4)
        val body = JSONObject().put("contents", contents).put("generationConfig", genConfig)

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var attempt = 0
        while (true) {
            val (code, respBody) = client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            if (code in 200..299) return parseAnalysis(respBody)

            val retryable = code == 429 || code == 500 || code == 503
            if (retryable && attempt < BACKOFF_MS.size) {
                Thread.sleep(BACKOFF_MS[attempt])
                attempt++
                continue
            }

            if (code == 400 && respBody.contains("API key not valid")) {
                throw RuntimeException("Your Gemini API key is invalid.")
            }
            if (code == 503) throw RuntimeException("Gemini is overloaded right now — try this reel again in a minute.")
            if (code == 429) throw RuntimeException("Your Gemini key hit its rate limit — wait a minute and retry.")
            if (code == 404) throw RuntimeException("That Gemini model isn't available on your key — pick another in Settings.")
            throw RuntimeException("Gemini $code: ${respBody.take(180)}")
        }
    }

    private fun parseAnalysis(respBody: String): Analysis {
        val json = JSONObject(respBody)
        val text = json.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")
        val o = JSONObject(text)
        val tagsArr = o.optJSONArray("subtags") ?: JSONArray()
        val tags = (0 until tagsArr.length()).map { tagsArr.getString(it) }
        return Analysis(
            title = o.optString("title"),
            summary = o.optString("summary"),
            transcript = o.optString("transcript"),
            topic = o.optString("topic", "Other"),
            subtags = tags,
            language = o.optString("language"),
            hasSpeech = o.optBoolean("hasSpeech", true),
        )
    }

    /** YouTube: Gemini ingests the URL directly, no download needed. */
    fun analyzeYoutube(apiKey: String, model: String, url: String, caption: String): Analysis {
        val part = JSONObject().put("file_data", JSONObject().put("file_uri", url))
        return run(apiKey, model, listOf(part), captioned(PROMPT, caption))
    }

    /** Instagram/TikTok/etc: send the downloaded audio inline as base64. */
    fun analyzeInlineAudio(apiKey: String, model: String, base64: String, mimeType: String, caption: String): Analysis {
        val part = JSONObject().put(
            "inline_data",
            JSONObject().put("mime_type", mimeType).put("data", base64),
        )
        return run(apiKey, model, listOf(part), captioned(PROMPT, caption))
    }

    /** Screenshots: send one or more images inline for OCR + understanding. */
    fun analyzeImages(apiKey: String, model: String, images: List<Pair<String, String>>): Analysis {
        val parts = images.map { (base64, mime) ->
            JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", base64))
        }
        return run(apiKey, model, parts, IMAGE_PROMPT)
    }
}
