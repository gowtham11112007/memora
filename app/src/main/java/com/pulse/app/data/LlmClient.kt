package com.pulse.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The user pastes these into the app, so no key is ever in the code or Git. */
data class AiConfig(val apiKey: String = "", val shareTotals: Boolean = false) {
    val isSet: Boolean get() = apiKey.isNotBlank()
}

data class EmailConfig(val resendKey: String = "", val toAddress: String = "", val weekly: Boolean = false) {
    val isSet: Boolean get() = resendKey.isNotBlank() && toAddress.contains("@")
}

class AiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)

    fun load() = AiConfig(prefs.getString("key", "").orEmpty(), prefs.getBoolean("totals", false))

    fun save(config: AiConfig) {
        prefs.edit().putString("key", config.apiKey.trim()).putBoolean("totals", config.shareTotals).apply()
    }

    fun loadEmail() = EmailConfig(
        prefs.getString("resend", "").orEmpty(),
        prefs.getString("to", "").orEmpty(),
        prefs.getBoolean("weekly", false),
    )

    fun saveEmail(config: EmailConfig) {
        prefs.edit()
            .putString("resend", config.resendKey.trim())
            .putString("to", config.toAddress.trim())
            .putBoolean("weekly", config.weekly)
            .apply()
    }

    fun isPaused(): Boolean = prefs.getBoolean("paused", false)

    fun setPaused(paused: Boolean) = prefs.edit().putBoolean("paused", paused).apply()

    fun clear() = prefs.edit().clear().apply()
}

/** role is "user" or "model", as Gemini expects. */
data class LlmMessage(val role: String, val content: String)

sealed interface LlmResult {
    data class Reply(val text: String) : LlmResult
    data class Failed(val reason: String) : LlmResult
}

/**
 * Calls Google's Gemini API directly from the phone. Blocking, so call it from Dispatchers.IO.
 * If a model is overloaded, it tries the lighter one. Callers send only what the user typed.
 */
class GeminiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) {
    fun chat(system: String, messages: List<LlmMessage>): LlmResult {
        var last: LlmResult = LlmResult.Failed(BUSY)
        for (model in MODELS) {
            val (code, body) = try {
                post(model, requestBody(system, messages))
            } catch (e: IOException) {
                return LlmResult.Failed("I can't reach Gemini. Check your internet connection.")
            }
            last = when (code) {
                in 200..299 -> return parseReply(body)
                400, 401, 403 -> return LlmResult.Failed("Gemini refused the key. Check it in Settings → AI and email.")
                else -> LlmResult.Failed(BUSY)
            }
        }
        return last
    }

    private fun post(model: String, body: String): Pair<Int, String> {
        val connection = URL("$baseUrl/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-goog-api-key", apiKey)
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val text = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else ""
            return code to text
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBody(system: String, messages: List<LlmMessage>): String {
        val contents = JSONArray()
        messages.forEach { message ->
            contents.put(
                JSONObject()
                    .put("role", message.role)
                    .put("parts", JSONArray().put(JSONObject().put("text", message.content))),
            )
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .toString()
    }

    private fun parseReply(body: String): LlmResult {
        val text = runCatching {
            val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        }.getOrNull()?.trim()
        return if (text.isNullOrEmpty()) LlmResult.Failed("Gemini sent an empty answer. Try again.") else LlmResult.Reply(text)
    }

    private companion object {
        val MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")
        const val BUSY = "Gemini is busy or out of free quota right now. Try again in a minute."
    }
}

/** Sends one plain-text email through Resend. Blocking, so call it from Dispatchers.IO. */
class ResendClient(private val apiKey: String, private val baseUrl: String = "https://api.resend.com") {
    /** Returns null on success, or a reason the user can act on. */
    fun send(to: String, subject: String, text: String): String? {
        val connection = URL("$baseUrl/emails").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val body = JSONObject()
                .put("from", "Memora <onboarding@resend.dev>")
                .put("to", JSONArray().put(to))
                .put("subject", subject)
                .put("text", text)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            when (val code = connection.responseCode) {
                in 200..299 -> null
                401, 403 -> "Resend refused this. Check the key. Without your own domain, Resend only sends to your account's email."
                else -> "Resend had a problem (error $code). Try again later."
            }
        } catch (e: IOException) {
            "I can't reach Resend. Check your internet connection."
        } finally {
            connection.disconnect()
        }
    }
}
