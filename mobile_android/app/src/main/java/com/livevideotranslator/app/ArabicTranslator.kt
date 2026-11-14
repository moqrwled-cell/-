package com.livevideotranslator.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object ArabicTranslator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    private val cache = object : LinkedHashMap<String, String>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 400
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

        // 1. Google Chrome Extension Endpoint السريعة جداً (~40ms)
        try {
            val encoded = URLEncoder.encode(cleaned, "UTF-8")
            val url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=ar&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return ""
                    val jsonArray = JSONArray(body)
                    if (jsonArray.length() > 0) {
                        val trans = jsonArray.optString(0, "").trim()
                        if (containsArabic(trans)) {
                            synchronized(cache) { cache[cleaned] = trans }
                            return trans
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        // 2. Google GTX Endpoint كخيار بديل
        try {
            val encoded = URLEncoder.encode(cleaned, "UTF-8")
            val url2 = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t&q=$encoded"
            val request2 = Request.Builder()
                .url(url2)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request2).execute().use { response ->
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
                        synchronized(cache) { cache[cleaned] = result }
                        return result
                    }
                }
            }
        } catch (e: Exception) {}

        return ""
    }
}
