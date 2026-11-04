package com.duiyi.android.domain

object TranslationPolicy {
    const val FIRST_TRANSLATION_MS = 280L
    const val TRANSLATION_MS = 450L
    const val VOICE_FIRST_MS = 200L
    const val VOICE_NEXT_MS = 350L
    const val PERSIST_MS = 250L
    const val IDLE_RESTORE_MS = 30_000L
    const val SPEECH_QUEUE_LIMIT = 2
    const val CROSSTALK_WINDOW_MS = 1_000L

    private val terminals = charArrayOf('，', '。', '！', '？', '；', ',', '.', '!', '?', ';', ':')

    fun endsWithClause(text: String): Boolean {
        val last = text.lastOrNull() ?: return false
        return last in terminals
    }

    /**
     * Index just past the last clause-ending punctuation, or -1 when there is none.
     * A translation chunk must not be cut anywhere else: a fragment with no boundary
     * has no guarantee of being a translatable unit.
     */
    fun lastClauseBoundary(text: String): Int {
        for (index in text.indices.reversed()) {
            if (text[index] in terminals) return index + 1
        }
        return -1
    }

    fun isWorthTranslating(text: String, language: AppLanguage): Boolean = when (language) {
        AppLanguage.ZhHans -> hanCount(text) >= 4
        AppLanguage.English -> latinWords(text) >= 3 || letterCount(text) >= 12
        AppLanguage.Japanese -> text.count { it in '\u3040'..'\u30FF' || it in '\u4e00'..'\u9fff' } >= 4
        AppLanguage.Korean -> text.count { it in '\uAC00'..'\uD7AF' } >= 4
        AppLanguage.Thai -> text.count { it in '\u0E00'..'\u0E7F' } >= 4
        AppLanguage.Vietnamese -> latinWords(text) >= 3 || letterCount(text) >= 12
    }

    fun isSentenceLike(text: String, language: AppLanguage): Boolean {
        if (endsWithClause(text)) return true
        return when (language) {
            AppLanguage.ZhHans -> hanCount(text) >= 10
            AppLanguage.English, AppLanguage.Vietnamese -> latinWords(text) >= 6 || letterCount(text) >= 28
            AppLanguage.Japanese -> text.length >= 10
            AppLanguage.Korean -> text.count { it in '\uAC00'..'\uD7AF' } >= 8
            AppLanguage.Thai -> text.count { it in '\u0E00'..'\u0E7F' } >= 12
        }
    }

    fun isPacked(text: String, language: AppLanguage): Boolean = when (language) {
        AppLanguage.English, AppLanguage.Vietnamese -> latinWords(text) >= 20
        else -> text.length >= 80
    }

    fun isConfident(text: String, language: AppLanguage): Boolean = isWorthTranslating(text, language)

    fun latinWords(text: String): Int =
        text.split(Regex("\\s+")).count { word -> word.any { it.isLetter() && it.code < 0x80 } }

    fun hanCount(text: String): Int = text.count { it in '\u4e00'..'\u9fff' }

    fun letterCount(text: String): Int = text.count { it.isLetter() }
}
