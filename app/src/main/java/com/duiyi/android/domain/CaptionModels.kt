package com.duiyi.android.domain

import java.util.UUID

data class CaptionLine(
    val id: UUID = UUID.randomUUID(),
    val side: SpeakerSide,
    val source: AppLanguage,
    val sourceText: String,
    val translatedText: String = "",
    val phase: Phase = Phase.Streaming,
) {
    enum class Phase { Streaming, Settled }

    val displayText: String
        get() = translatedText.ifEmpty { sourceText }

    fun visibleText(forViewer: AppLanguage): String {
        if (translatedText.isNotEmpty()) return translatedText
        if (sourceText.isNotBlank()) return sourceText
        return if (source == forViewer) "" else "…"
    }
}

data class PaneState(
    val history: List<CaptionLine> = emptyList(),
    val live: CaptionLine? = null,
    val speakingHint: String? = null,
) {
    fun applyLive(line: CaptionLine): PaneState {
        val current = live
        return if (current != null && (current.id == line.id || shouldMerge(line, current))) {
            copy(live = merge(current, line)).absorbRefinement()
        } else {
            archiveLive().copy(live = line).absorbRefinement()
        }
    }

    fun applyTranslation(utteranceId: UUID, text: String, settle: Boolean): PaneState {
        live?.takeIf { it.id == utteranceId }?.let { current ->
            return copy(
                live = current.copy(
                    translatedText = SpokenText.preferredTranslation(current.translatedText, text, settle),
                    phase = if (settle) CaptionLine.Phase.Settled else current.phase,
                ),
            ).absorbRefinement()
        }
        val index = history.indexOfFirst { it.id == utteranceId }
        if (index < 0) return this
        val updated = history.toMutableList()
        val item = updated[index]
        updated[index] = item.copy(
            translatedText = SpokenText.preferredTranslation(item.translatedText, text, settle),
            phase = if (settle) CaptionLine.Phase.Settled else item.phase,
        )
        return copy(history = updated).absorbRefinement()
    }

    fun archiveLive(): PaneState {
        val current = live ?: return this
        if (current.sourceText.isEmpty()) return copy(live = null)
        val archived = current.copy(phase = CaptionLine.Phase.Settled)
        val nextHistory = if (history.lastOrNull()?.id == archived.id) {
            history.dropLast(1) + archived
        } else {
            history + archived
        }
        return copy(history = nextHistory.takeLast(HISTORY_LIMIT), live = null)
    }

    fun resetCaptions(): PaneState = PaneState()

    fun withHint(hint: String?): PaneState = copy(speakingHint = hint)

    private fun absorbRefinement(): PaneState {
        var current = live ?: return this
        val hist = history.toMutableList()
        while (hist.isNotEmpty() && shouldMerge(current, hist.last())) {
            // Android 14 List has no removeLast(); Kotlin 2 + JDK 21 would emit that call and crash.
            val last = hist.removeAt(hist.lastIndex)
            current = merge(last, current)
        }
        return copy(history = hist.takeLast(HISTORY_LIMIT), live = current)
    }

    private fun shouldMerge(line: CaptionLine, existing: CaptionLine): Boolean {
        if (existing.source != line.source || existing.side != line.side) return false
        if (existing.id == line.id) return true
        return SpokenText.isContinuation(line.sourceText, existing.sourceText, line.source)
    }

    private fun merge(current: CaptionLine, incoming: CaptionLine): CaptionLine = current.copy(
        sourceText = SpokenText.preferredSource(current.sourceText, incoming.sourceText),
        translatedText = if (incoming.translatedText.isEmpty()) {
            current.translatedText
        } else {
            SpokenText.preferredTranslation(
                current.translatedText,
                incoming.translatedText,
                settle = incoming.phase == CaptionLine.Phase.Settled,
            )
        },
        phase = incoming.phase,
        source = incoming.source,
        side = incoming.side,
    )

    companion object {
        const val HISTORY_LIMIT = 40
        val Empty = PaneState()
    }
}

object SpokenText {
    fun isContinuation(incoming: String, previous: String, language: AppLanguage): Boolean {
        val incomingText = incoming.trim()
        val previousText = previous.trim()
        if (incomingText.isEmpty() || previousText.isEmpty()) return false
        if (incomingText == previousText) return true
        if (incomingText.startsWith(previousText)) return true
        if (previousText.startsWith(incomingText)) {
            val minimum = maxOf(minimumStem(language), (previousText.length * 0.55).toInt() + 1)
            return incomingText.length >= minimum
        }
        val shared = incomingText.zip(previousText).takeWhile { it.first == it.second }.count()
        val shorter = minOf(incomingText.length, previousText.length)
        if (shorter == 0) return false
        return shared >= minimumStem(language) && shared.toDouble() / shorter >= 0.55
    }

    fun isRefinement(incoming: String, previous: String): Boolean {
        val a = incoming.replace("\\s".toRegex(), "")
        val b = previous.replace("\\s".toRegex(), "")
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b || a.startsWith(b) || b.startsWith(a)) return true
        if (a.contains(b) && b.length >= 8) return true
        val shorter = minOf(a.length, b.length)
        val shared = a.zip(b).takeWhile { it.first == it.second }.count()
        return shorter >= 4 && shared.toDouble() / shorter >= 0.5
    }

    fun preferredSource(current: String, incoming: String): String =
        if (incoming.length >= current.length) incoming else current

    fun preferredTranslation(current: String, incoming: String, settle: Boolean = false): String {
        if (incoming.isEmpty()) return current
        if (current.isEmpty() || settle) return incoming
        return if (incoming.length >= current.length) incoming else current
    }

    fun inferFromScript(text: String, fallback: AppLanguage): AppLanguage {
        val han = text.count { it in '\u4e00'..'\u9fff' }
        val hiragana = text.count { it in '\u3040'..'\u309F' }
        val hangul = text.count { it in '\uAC00'..'\uD7AF' }
        val thai = text.count { it in '\u0E00'..'\u0E7F' }
        val latin = text.count { it.isLetter() && it.code < 0x80 }
        return when {
            hangul > 0 -> AppLanguage.Korean
            hiragana > 0 -> AppLanguage.Japanese
            thai > 0 -> AppLanguage.Thai
            han > latin -> AppLanguage.ZhHans
            latin > han && latin > 0 -> AppLanguage.English
            else -> fallback
        }
    }

    fun scriptIsDecisive(text: String): Boolean {
        val han = text.count { it in '\u4e00'..'\u9fff' }
        val latin = text.count { it.isLetter() && it.code < 0x80 }
        return kotlin.math.abs(han - latin) >= 2 && (han >= 2 || latin >= 3)
    }

    fun matchesPrimaryScript(text: String, primary: AppLanguage): Boolean {
        if (!scriptIsDecisive(text)) return true
        return inferFromScript(text, primary) == primary
    }

    fun normalizeAsr(text: String, language: AppLanguage): String {
        val trimmed = text.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return trimmed
        if (!language.usesLatinScript) return trimmed
        val letters = trimmed.filter { it.isLetter() }
        if (letters.length < 4) return trimmed
        val upper = letters.count { it.isUpperCase() }
        if (upper * 5 < letters.length * 4) return trimmed
        val lower = trimmed.lowercase()
        return lower.replaceFirstChar { if (it.isLetter()) it.titlecase() else it.toString() }
    }

    fun joinSegments(current: String, incoming: String, language: AppLanguage): String {
        val left = current.trim()
        val right = incoming.trim()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        if (right.startsWith(left) || isContinuation(right, left, language)) {
            return if (right.length >= left.length) right else left
        }
        if (left.startsWith(right)) return left
        val sep = if (language.usesLatinScript) " " else ""
        return left + sep + right
    }

    private fun minimumStem(language: AppLanguage): Int =
        if (language.usesLatinScript) 3 else 2
}
