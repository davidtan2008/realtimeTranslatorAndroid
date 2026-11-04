package com.duiyi.android.domain.routing

import com.duiyi.android.domain.TranslationPolicy
import java.util.UUID

/**
 * Decides how much of a still-growing ASR hypothesis is safe to translate.
 *
 * This is the corrective to the earlier attempt at translating intermediate results,
 * which re-translated the whole partial hypothesis on every update and overwrote the
 * caption. That produced text that flickered and never settled.
 *
 * Two rules make a region safe instead:
 *
 * 1. **Agreement.** A region is only considered if it is unchanged between the two most
 *    recent hypotheses. SenseVoice re-decodes the whole accumulated utterance each time,
 *    so a boundary that survives one full second of new audio is very unlikely to move.
 * 2. **A clause boundary.** The region must end at sentence or clause punctuation. Cutting
 *    anywhere else hands the translator a fragment that is not a translatable unit.
 *
 * Together these mean a released chunk is only ever *appended* by the caller — nothing
 * already on screen is ever revised, which is what keeps the result readable.
 *
 * Pure Kotlin with the clock owned by the caller, so it is testable on the JVM.
 */
class StablePrefixTracker(private val minChunkChars: Int = 2) {
    private class State {
        var lastText: String = ""
        var committed: Int = 0
    }

    private val states = mutableMapOf<UUID, State>()

    /**
     * Feed the latest hypothesis. Returns the newly stable source text for this utterance,
     * or "" when nothing new is safe to translate yet.
     */
    fun update(utteranceId: UUID, text: String, isFinal: Boolean): String {
        val state = states.getOrPut(utteranceId) { State() }
        val trimmed = text.trim()
        val committed = state.committed.coerceAtMost(trimmed.length)

        if (isFinal) {
            // The speaker stopped; whatever is left is as stable as it will ever get.
            state.lastText = trimmed
            state.committed = trimmed.length
            return trimmed.substring(committed)
        }

        val previous = state.lastText
        state.lastText = trimmed
        if (previous.isEmpty() || trimmed.isEmpty()) return ""

        val agreed = commonPrefixLength(previous, trimmed)
        if (agreed <= committed) return ""
        val boundary = TranslationPolicy.lastClauseBoundary(trimmed.substring(0, agreed))
        if (boundary <= committed) return ""

        val chunk = trimmed.substring(committed, boundary)
        // Chunks shorter than this are mostly punctuation noise and cost a full
        // generation each, so fold them into the next one instead.
        if (chunk.length < minChunkChars) return ""

        state.committed = boundary
        return chunk
    }

    fun forget(utteranceId: UUID) {
        states.remove(utteranceId)
    }

    fun reset() {
        states.clear()
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val shorter = minOf(a.length, b.length)
        var index = 0
        while (index < shorter && a[index] == b[index]) index++
        return index
    }
}
