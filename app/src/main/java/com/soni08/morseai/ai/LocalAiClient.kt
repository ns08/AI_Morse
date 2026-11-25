package com.soni08.morseai.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android端末内（Termux上のOllama/llama.cpp等）または同一LAN上の
 * ローカルAI（OpenAI互換 /chat/completions エンドポイント）へ直接問い合わせるクライアント
 */
object LocalAiClient {

    fun generateHiraganaReply(
        endpointUrl: String,
        modelName: String,
        userText: String
    ): String? {
        if (endpointUrl.isBlank()) return null
        return try {
            val url = URL(endpointUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            val systemPrompt = "あなたは音声合成(TTS)で読み上げられるアシスタントです。返答はすべてひらがな（必要に応じて読点や句点のみ）で出力してください。漢字やカタカナや記号や絵文字は使わず、短く自然な話し言葉で答えてください。"

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            }

            val payload = JSONObject().apply {
                put("model", modelName.ifBlank { "gemma2:2b" })
                put("messages", messages)
                put("temperature", 0.7)
                put("max_tokens", 256)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code !in 200..299 || body.isBlank()) return null

            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val msgObj = choices.getJSONObject(0).optJSONObject("message") ?: return null
            val content = msgObj.optString("content", "").trim()
            if (content.isBlank()) return null
            content.replace(Regex("[*#`]"), "").trim()
        } catch (_: Exception) {
            null
        }
    }
}
