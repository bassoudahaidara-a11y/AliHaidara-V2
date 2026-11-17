package com.ali.haidara.lecteur

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Option facultative : avec une clé Groq et internet, les fautes de lecture du texte sont corrigées. */
object Groq {
    private const val SYSTEM =
        "Le texte vient d'un cahier d'école lu par OCR, il peut avoir des fautes de lecture. " +
        "Corrige seulement ces fautes. Ne résume pas, ne change pas le sens, garde les phrases et les retours à la ligne. " +
        "Réponds uniquement avec le texte corrigé, sans commentaire."

    suspend fun correct(key: String, text: String): String? = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            val body = JSONObject()
                .put("model", "llama-3.1-8b-instant")
                .put("temperature", 0.1)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", text)))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) return@withContext null
            val resp = c.inputStream.bufferedReader().readText()
            JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
