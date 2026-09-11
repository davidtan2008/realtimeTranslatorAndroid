package com.duiyi.android.domain.routing

import com.duiyi.android.domain.AppLanguage
import java.util.UUID

data class Clause(
    val id: UUID = UUID.randomUUID(),
    val utteranceId: UUID,
    val text: String,
    val language: AppLanguage,
)

object ClauseSplitter {
    private val terminals = charArrayOf('，', '。', '！', '？', '；', ',', '.', '!', '?', ';', ':')

    fun consume(
        utteranceId: UUID,
        fullText: String,
        alreadySpokenLength: Int,
        language: AppLanguage,
        settled: Boolean,
    ): Pair<List<Clause>, Int> {
        if (fullText.length <= alreadySpokenLength) return emptyList<Clause>() to alreadySpokenLength
        val suffix = fullText.substring(alreadySpokenLength)
        val canSpeak = settled || (suffix.lastOrNull()?.let { it in terminals } == true)
        if (!canSpeak) return emptyList<Clause>() to alreadySpokenLength
        val clause = Clause(utteranceId = utteranceId, text = suffix, language = language)
        return listOf(clause) to fullText.length
    }
}
