package com.example.livetranslator

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class LibreTranslateApi(
    private val apiUrl: String = "https://translate.argosopentech.com/translate",
    private val onTranslationComplete: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = OkHttpClient()

    fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        apiKey: String? = null
    ) {
        // Map our language codes to LibreTranslate language codes
        val sourceCode = mapLanguageCode(sourceLanguage)
        val targetCode = mapLanguageCode(targetLanguage)

        val json = JSONObject().apply {
            put("q", text)
            put("source", sourceCode)
            put("target", targetCode)
            put("format", "text")
            apiKey?.let { put("api_key", it) }
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    onError("API error: ${response.code}")
                    return
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    onError("Empty response from translation API")
                    return
                }

                try {
                    val jsonResponse = JSONObject(responseBody)
                    val translatedText = jsonResponse.getString("translatedText")
                    onTranslationComplete(translatedText)
                } catch (e: Exception) {
                    onError("Parse error: ${e.message}")
                }
            }
        })
    }

    // Map our language codes to LibreTranslate codes
    private fun mapLanguageCode(code: String): String {
        return when (code) {
            "zh" -> "zh"
            "en" -> "en"
            "ja" -> "ja"
            "ko" -> "ko"
            "es" -> "es"
            "fr" -> "fr"
            "de" -> "de"
            "ru" -> "ru"
            else -> "en"
        }
    }
}