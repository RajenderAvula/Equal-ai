package com.equalai.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Net {
    fun isOnline(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Google AI Studio key pasted in Setup. Sent in a header, never in the URL.
 * Text prompts and audio (WAV, inline) both go through the same generateContent endpoint.
 */
object GeminiClient {

    /** Why the last request failed (HTTP code and Google's own message), shown next to summaries and in Setup. */
    @Volatile var lastError: String = ""

    val fallbackModels = listOf(
        "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-flash-latest", "gemini-2.0-flash"
    )

    /** Models this API key can use for generateContent, straight from Google. Null if the request failed. */
    suspend fun listModels(): List<String>? = withContext(Dispatchers.IO) {
        val key = AppSettings.apiKey
        if (key.isBlank()) { lastError = "No API key set."; return@withContext null }
        var conn: HttpURLConnection? = null
        try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection
            conn = c
            c.setRequestProperty("x-goog-api-key", key)
            c.connectTimeout = 8_000
            c.readTimeout = 15_000
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) { lastError = describe(code, text); return@withContext null }
            val arr = JSONObject(text).optJSONArray("models") ?: return@withContext emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val name = m.optString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")
                var ok = false
                if (methods != null) for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
                val bad = listOf("embedding", "tts", "image", "live", "imagen", "veo", "aqa", "robotics", "computer-use", "native-audio")
                if (ok && name.startsWith("gemini") && bad.none { name.contains(it) }) out.add(name)
            }
            out.sorted()
        } catch (e: Exception) {
            lastError = e.localizedMessage ?: "network error"
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun describe(code: Int, body: String): String {
        val msg = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (e: Exception) { "" }
        val hint = when (code) {
            400 -> if (msg.contains("API key", true)) "API key not valid." else ""
            403 -> "API key not allowed for this model or project."
            404 -> "Model name not found: pick another one in Setup."
            429 -> "Free quota used up for now: wait a minute or pick another model."
            503 -> "Gemini is busy: try again or pick another model."
            else -> ""
        }
        return "HTTP $code. ${hint.ifBlank { msg }}".trim().take(200)
    }

    suspend fun generate(prompt: String, json: Boolean = false): String? =
        call(JSONArray().put(JSONObject().put("text", prompt)), json, 25_000)

    suspend fun generateWithAudio(prompt: String, wav: ByteArray, json: Boolean = true, mime: String = "audio/wav"): String? {
        val inline = JSONObject()
            .put("mimeType", mime)
            .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inlineData", inline))
        return call(parts, json, 45_000)
    }

    @Volatile private var retrying = false

    private suspend fun call(parts: JSONArray, json: Boolean, readTimeout: Int): String? =
        withContext(Dispatchers.IO) {
            val key = AppSettings.apiKey
            if (key.isBlank()) return@withContext null
            var conn: HttpURLConnection? = null
            try {
                val endpoint =
                    "https://generativelanguage.googleapis.com/v1beta/models/${AppSettings.modelName}:generateContent"
                val c = URL(endpoint).openConnection() as HttpURLConnection
                conn = c
                c.requestMethod = "POST"
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", key)
                c.doOutput = true
                c.connectTimeout = 8_000
                c.readTimeout = readTimeout

                val body = JSONObject().put(
                    "contents",
                    JSONArray().put(JSONObject().put("parts", parts))
                )
                if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                c.outputStream.use { it.write(body.toString().toByteArray()) }

                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    lastError = describe(code, text)
                    AssistantBridge.log("Gemini: $lastError (model ${AppSettings.modelName})")
                    if ((code == 404 || code == 503 || code == 429) && !retrying) {
                        // this model is unavailable: try the next usable one once, and keep it if it works
                        val alt = (listModels() ?: fallbackModels)
                            .filter { it != AppSettings.modelName }
                            .sortedBy { if (it.contains("flash") && !it.contains("lite")) 0 else 1 }
                            .firstOrNull()
                        if (alt != null) {
                            val old = AppSettings.modelName
                            AppSettings.modelName = alt
                            retrying = true
                            val r = try { call(parts, json, readTimeout) } finally { retrying = false }
                            if (r == null) AppSettings.modelName = old
                            else AssistantBridge.log("Gemini model switched to $alt")
                            return@withContext r
                        }
                    }
                    return@withContext null
                }
                lastError = ""
                JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            } catch (e: Exception) {
                lastError = "Network problem: ${e.localizedMessage ?: "no connection"}"
                AssistantBridge.log("Gemini error: ${e.localizedMessage}")
                null
            } finally {
                conn?.disconnect()
            }
        }
}
