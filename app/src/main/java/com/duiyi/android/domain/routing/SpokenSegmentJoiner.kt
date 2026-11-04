package com.duiyi.android.domain.routing

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.SpokenText
import com.duiyi.android.domain.TranslationPolicy
import com.duiyi.android.domain.Utterance
import java.util.UUID

class SpokenSegmentJoiner {
    private var utteranceId: UUID = UUID.randomUUID()
    private var language: AppLanguage? = null
    private val buffer = StringBuilder()
    private var lastIngestNs = 0L

    /**
     * How long the most recent release sat in the buffer, or -1 if the last flush
     * released nothing measurable (a force-flush has no caller timestamp).
     * Read by the latency probe; not used for control flow.
     */
    var lastReleaseHeldNs: Long = -1L
        private set

    fun ingest(nowNs: Long, language: AppLanguage, text: String, isFinal: Boolean): List<Utterance> {
        val normalized = SpokenText.normalizeAsr(text, language)
        if (normalized.isEmpty()) return emptyList()
        val out = mutableListOf<Utterance>()
        val heldLanguage = this.language
        if (heldLanguage != null && heldLanguage != language && buffer.isNotEmpty()) {
            flush(nowNs)?.let { out += it }
        }
        if (!isFinal) {
            out += Utterance(utteranceId, language, preview(language, normalized), false)
            return out
        }
        val held = buffer.toString()
        if (held.isNotEmpty() &&
            TranslationPolicy.isSentenceLike(normalized, language) &&
            !SpokenText.isContinuation(normalized, held, language)
        ) {
            flush(nowNs)?.let { out += it }
        }
        append(language, normalized)
        lastIngestNs = nowNs
        val combined = buffer.toString()
        if (shouldRelease(combined, language)) {
            flush(nowNs)?.let { out += it }
        } else {
            out += Utterance(utteranceId, language, combined, false)
        }
        return out
    }

    fun tick(nowNs: Long): Utterance? {
        if (buffer.isEmpty()) return null
        if (nowNs - lastIngestNs < HOLD_NS) return null
        return flush(nowNs)
    }

    /** No caller timestamp available here, so this release reports no hold duration. */
    fun forceFlush(): Utterance? = flush(0L)

    private fun preview(language: AppLanguage, text: String): String {
        if (buffer.isEmpty() || this.language != language) return text
        return SpokenText.joinSegments(buffer.toString(), text, language)
    }

    private fun append(language: AppLanguage, text: String) {
        if (buffer.isEmpty()) {
            this.language = language
            buffer.append(text)
            return
        }
        val joined = SpokenText.joinSegments(buffer.toString(), text, language)
        buffer.setLength(0)
        buffer.append(joined)
        this.language = language
    }

    private fun shouldRelease(text: String, language: AppLanguage): Boolean =
        TranslationPolicy.isSentenceLike(text, language) || TranslationPolicy.isPacked(text, language)

    private fun flush(nowNs: Long): Utterance? {
        val held = language ?: return null
        val text = buffer.toString().trim()
        if (text.isEmpty()) {
            reset()
            return null
        }
        lastReleaseHeldNs = if (nowNs > 0L && lastIngestNs > 0L && nowNs >= lastIngestNs) {
            nowNs - lastIngestNs
        } else {
            -1L
        }
        val utterance = Utterance(utteranceId, held, text, true)
        reset()
        return utterance
    }

    private fun reset() {
        utteranceId = UUID.randomUUID()
        language = null
        buffer.clear()
        lastIngestNs = 0L
    }

    companion object {
        const val HOLD_NS = 1_400_000_000L
    }
}
