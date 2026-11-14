package com.livevideotranslator.app

import android.content.Context
import android.content.SharedPreferences

object AppConfig {
    private const val PREFS_NAME = "LiveVideoTranslatorPrefs"
    private const val KEY_GROQ = "groq_api_key"
    private const val KEY_BILINGUAL = "is_bilingual"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_OPACITY = "overlay_opacity"

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

    fun getFontSize(context: Context): Float {
        return getPrefs(context).getFloat(KEY_FONT_SIZE, 16f)
    }

    fun setFontSize(context: Context, size: Float) {
        getPrefs(context).edit().putFloat(KEY_FONT_SIZE, size).apply()
    }

    fun getOpacity(context: Context): Float {
        return getPrefs(context).getFloat(KEY_OPACITY, 0.75f)
    }

    fun setOpacity(context: Context, opacity: Float) {
        getPrefs(context).edit().putFloat(KEY_OPACITY, opacity).apply()
    }
}
