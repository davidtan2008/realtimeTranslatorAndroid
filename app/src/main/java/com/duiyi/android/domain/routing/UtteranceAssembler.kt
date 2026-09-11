package com.duiyi.android.domain.routing

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.SpeakerSide
import com.duiyi.android.domain.SpokenText
import com.duiyi.android.domain.Utterance
import java.util.UUID

class UtteranceAssembler {
    var lastLanguage: AppLanguage? = null
        private set

    private var utteranceId: UUID = UUID.randomUUID()
    private val texts = mutableMapOf<AppLanguage, String>()
    private var lockedLanguage: AppLanguage? = null

    fun ingest(language: AppLanguage, text: String, isFinal: Boolean): Utterance? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (!matchesClaimedLanguage(trimmed, language)) return null

        val locked = lockedLanguage
        if (locked != null && language != locked && !isFinal) {
            val incumbent = texts[locked].orEmpty()
            if (incumbent.isNotEmpty() && !SpokenText.isContinuation(trimmed, incumbent, language)) {
                return null
            }
        }

        if (locked != null && language != locked && isFinal) {
            startNewUtterance()
        }

        texts[language] = trimmed
        if (lockedLanguage == null) lockedLanguage = language
        val winner = lockedLanguage ?: return null
        if (winner != language && !isFinal) return null
        lastLanguage = winner
        val utterance = Utterance(
            id = utteranceId,
            language = winner,
            text = texts[winner].orEmpty(),
            isFinal = isFinal && winner == language,
        )
        if (utterance.isFinal) startNewUtterance()
        return utterance
    }

    fun silenceTimedOut(): Utterance? {
        val winner = lockedLanguage ?: lastLanguage ?: return null
        val text = texts[winner].orEmpty()
        if (text.isEmpty()) return null
        val utterance = Utterance(utteranceId, winner, text, isFinal = true)
        beginNext(winner)
        return utterance
    }

    fun reset() {
        startNewUtterance()
    }

    fun startNewUtterance() {
        utteranceId = UUID.randomUUID()
        texts.clear()
        lockedLanguage = null
    }

    private fun beginNext(keeping: AppLanguage) {
        utteranceId = UUID.randomUUID()
        texts.clear()
        lockedLanguage = keeping
    }

    companion object {
        fun matchesClaimedLanguage(text: String, claimed: AppLanguage): Boolean {
            val han = text.count { it in '\u4e00'..'\u9fff' }
            val latin = text.count { it.isLetter() && it.code < 0x80 }
            return when (claimed) {
                AppLanguage.ZhHans -> han * 2 >= latin || han > 0
                AppLanguage.English, AppLanguage.Vietnamese -> latin >= han
                else -> true
            }
        }

        fun isConfident(text: String, language: AppLanguage): Boolean =
            com.duiyi.android.domain.TranslationPolicy.isConfident(text, language)
    }
}

object LanguageRouter {
    fun targetSide(detected: AppLanguage, userLanguage: AppLanguage): SpeakerSide =
        if (detected == userLanguage) SpeakerSide.Peer else SpeakerSide.User

    fun targetLanguage(
        detected: AppLanguage,
        userLanguage: AppLanguage,
        peerLanguage: AppLanguage,
    ): AppLanguage = if (detected == userLanguage) peerLanguage else userLanguage

    fun speakerSide(detected: AppLanguage, userLanguage: AppLanguage): SpeakerSide =
        targetSide(detected, userLanguage).opposite
}
