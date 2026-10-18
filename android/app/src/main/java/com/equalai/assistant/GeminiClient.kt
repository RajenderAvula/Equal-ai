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
                    AssistantBridge.log("Gemini HTTP $code")
                    return@withContext null
                }
                JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            } catch (e: Exception) {
                AssistantBridge.log("Gemini error: ${e.localizedMessage}")
                null
            } finally {
                conn?.disconnect()
            }
        }
}
