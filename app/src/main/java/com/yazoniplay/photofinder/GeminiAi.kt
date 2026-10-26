package com.yazoniplay.photofinder

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal Gemini REST client. API key is supplied by the user and never bundled in source.
 * For a public production release, move calls behind an authenticated server proxy.
 */
suspend fun askGemini(
    context: Context,
    apiKey: String,
    prompt: String,
    imageUri: Uri? = null
): String = withContext(Dispatchers.IO) {
    require(apiKey.isNotBlank()) { "Add your Gemini API key in Settings first." }
    val parts = JSONArray().put(JSONObject().put("text", prompt))
    if (imageUri != null) {
        val bytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Couldn't read this photo.")
        val mime = context.contentResolver.getType(imageUri) ?: "image/jpeg"
        val imagePart = JSONObject().put(
            "inline_data",
            JSONObject()
                .put("mime_type", mime)
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        )
        parts.put(imagePart)
    }
    val payload = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        .put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 900))
    val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent")
        .openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 20000
        readTimeout = 60000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("x-goog-api-key", apiKey.trim())
    }
    try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(responseText).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Gemini request failed (HTTP $status). Check your key and API access.")
        }
        val response = JSONObject(responseText)
        val candidates = response.optJSONArray("candidates")
        val responseParts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("Gemini returned no answer. Try a different prompt or photo.")
        val result = buildString {
            for (i in 0 until responseParts.length()) {
                val text = responseParts.optJSONObject(i)?.optString("text").orEmpty()
                if (text.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(text)
                }
            }
        }.trim()
        result.ifBlank { throw IllegalStateException("Gemini returned an empty answer. Try again.") }
    } finally {
        connection.disconnect()
    }
}
