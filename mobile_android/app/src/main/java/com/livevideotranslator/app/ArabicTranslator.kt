package com.livevideotranslator.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object ArabicTranslator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val cache = object : LinkedHashMap<String, String>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 300
        }
    }

    fun containsArabic(text: String): Boolean {
        for (char in text) {
            if (char in '\u0600'..'\u06FF') return true
        }
        return false
    }

    fun translate(text: String): String {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return ""

        synchronized(cache) {
            cache[cleaned]?.let { return it }
        }

        try {
            val encoded = URLEncoder.encode(cleaned, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return ""
                    val jsonArray = JSONArray(body)
                    val parts = jsonArray.getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONArray(i)
                        sb.append(part.getString(0))
                    }
                    val result = sb.toString().trim()
                    if (containsArabic(result)) {
                        synchronized(cache) {
                            cache[cleaned] = result
                        }
                        return result
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ""
    }
}
