package com.duiyi.android.services.speech

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.SpokenText

object SenseVoiceText {
    fun strip(text: String): String =
        text.replace(Regex("<\\|[^|]+\\|>"), " ").replace(Regex("\\s+"), " ").trim()

    fun languageFrom(tag: String, text: String, fallback: AppLanguage): AppLanguage {
        val cleaned = tag.lowercase().replace(Regex("[^a-z]"), "")
        return when {
            cleaned.startsWith("zh") || cleaned == "yue" || cleaned == "zhcn" -> AppLanguage.ZhHans
            cleaned.startsWith("en") -> AppLanguage.English
            cleaned.startsWith("ja") -> AppLanguage.Japanese
            cleaned.startsWith("ko") -> AppLanguage.Korean
            else -> SpokenText.inferFromScript(text, fallback)
        }
    }
}
