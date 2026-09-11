package com.duiyi.android.services.translation

import com.duiyi.android.domain.AppLanguage

object TranslateGemmaPrompt {
    private val metaLabel = Regex(
        """\[(?:背景|语境|上下文|Context|context|CONTEXT|Background|background)\]\s*""",
    )

    data class Parts(val prefix: String, val suffix: String)

    fun languageCode(language: AppLanguage): String = when (language) {
        AppLanguage.ZhHans -> "zh-Hans"
        AppLanguage.English -> "en"
        AppLanguage.Japanese -> "ja"
        AppLanguage.Korean -> "ko"
        AppLanguage.Thai -> "th"
        AppLanguage.Vietnamese -> "vi"
    }

    fun languageName(language: AppLanguage): String = when (language) {
        AppLanguage.ZhHans -> "Chinese"
        AppLanguage.English -> "English"
        AppLanguage.Japanese -> "Japanese"
        AppLanguage.Korean -> "Korean"
        AppLanguage.Thai -> "Thai"
        AppLanguage.Vietnamese -> "Vietnamese"
    }

    @Suppress("UNUSED_PARAMETER")
    fun parts(
        from: AppLanguage,
        to: AppLanguage,
        text: String,
        previousSource: String = "",
    ): Parts {
        val sourceCode = languageCode(from)
        val targetCode = languageCode(to)
        val sourceName = languageName(from)
        val targetName = languageName(to)
        val prefix = buildString {
            append("<start_of_turn>user\n")
            append("You are a professional ")
            append(sourceName)
            append(" (")
            append(sourceCode)
            append(") to ")
            append(targetName)
            append(" (")
            append(targetCode)
            append(") translator. Your goal is to accurately convey the meaning and nuances of the original ")
            append(sourceName)
            append(" text while adhering to ")
            append(targetName)
            append(" grammar, vocabulary, and cultural sensitivities.\n")
            append("Produce only the ")
            append(targetName)
            append(" translation of this one utterance, without any additional explanations, commentary, or labels.\n")
            append("Please translate the following ")
            append(sourceName)
            append(" text into ")
            append(targetName)
            append(":\n\n\n")
        }
        val suffix = buildString {
            append(text.trim())
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        return Parts(prefix, suffix)
    }

    fun prefix(from: AppLanguage, to: AppLanguage): String = parts(from, to, ".", "").prefix

    fun build(
        from: AppLanguage,
        to: AppLanguage,
        text: String,
        previousSource: String = "",
    ): String {
        val split = parts(from, to, text, previousSource)
        return split.prefix + split.suffix
    }

    fun sanitize(raw: String): String {
        var text = raw.trim()
            .removePrefix("<start_of_turn>model")
            .removePrefix("Translation:")
            .removePrefix("译文：")
            .removePrefix("譯文：")
            .trim()
        text = text.replace(metaLabel, "")
        val cutPoints = listOf(
            "<end_of_turn>",
            "<start_of_turn>",
            "\nNote:",
            "\n备注",
            "\nExplanation:",
            "\nTranslator:",
        )
        cutPoints.forEach { marker ->
            val index = text.indexOf(marker)
            if (index >= 0) text = text.take(index)
        }
        text = text.trim()
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    fun maxOutputTokens(text: String): Int {
        val n = text.trim().length
        return (n + 16).coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
    }

    const val MIN_OUTPUT_TOKENS = 16
    const val MAX_OUTPUT_TOKENS = 64
}
