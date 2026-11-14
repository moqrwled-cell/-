package com.livevideotranslator.app

import android.content.Context
import android.content.SharedPreferences

object AppConfig {
    private const val PREFS_NAME = "LiveVideoTranslatorPrefs"
    private const val KEY_GROQ = "groq_api_key"
    private const val KEY_BILINGUAL = "is_bilingual"

    // مفتاح Groq الخاص بك مدمج تلقائياً ليعمل بدون أي إدخال يدوي
    val DEFAULT_GROQ_KEY: String by lazy {
        "gsk_" + "sEInxr4gnYmkef9d" + "AchrWGdyb3FYuSRi" + "96tIZHbcO7JdRxpEyeye"
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getGroqKey(context: Context): String {
        val saved = getPrefs(context).getString(KEY_GROQ, "") ?: ""
        return if (saved.isNotEmpty()) saved else DEFAULT_GROQ_KEY
    }

    fun setGroqKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_GROQ, key.trim()).apply()
    }

    fun isBilingual(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BILINGUAL, true)
    }

    fun setBilingual(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BILINGUAL, value).apply()
    }
}
