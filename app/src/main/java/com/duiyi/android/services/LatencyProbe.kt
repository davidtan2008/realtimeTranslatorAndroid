package com.duiyi.android.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the wall-clock time of one spoken turn actually goes.
 *
 * Until now the only measured numbers came from [com.duiyi.android.debug.TranslationSelfTestActivity],
 * which times Gemma in isolation: no microphone, no VAD, no concurrent SenseVoice decode.
 * That made "the translation is slow" an inference rather than a measurement. This probe
 * records the stages of a real turn as they happen.
 *
 * A turn is anchored at the last speech window the VAD saw, so [LatencyTurn.speechToDoneMs]
 * is what the user actually waits after they stop talking.
 *
 * Writers run on the mic reader, the decode worker, the translation worker and the main
 * thread. Contention is negligible (the busiest writer is the partial decoder at ~1 Hz),
 * so a single lock is enough and the call sites stay one-liners.
 */
@Singleton
class LatencyProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private val completed = ArrayDeque<LatencyTurn>()
    private val _report = MutableStateFlow(LatencyReport())
    val report: StateFlow<LatencyReport> = _report.asStateFlow()

    private var nextSeq = 1L
    private var current = LatencyTurn(seq = 0, startedAtMs = 0L)
    private var speechAtNs = 0L

    /** Latest speech window the VAD accepted. Called ~31x/second while someone talks. */
    fun onSpeechWindow() {
        speechAtNs = System.nanoTime()
    }

    /** A segment came off the VAD queue. Opens a new turn. */
    fun onVadEndpoint(audioSamples: Int, sampleRate: Int) {
        val nowNs = System.nanoTime()
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            // A turn that produced a final but was never translated (the assembler dropped it,
            // or nobody asked for a translation) still belongs in the log, flagged with no gen.
            if (current.hasFinal && current.genMs < 0) archiveLocked()
            val anchor = if (speechAtNs > 0L && nowNs >= speechAtNs) speechAtNs else nowNs
            current = LatencyTurn(
                seq = nextSeq++,
                startedAtMs = nowMs,
                anchorNs = anchor,
                speechToEndpointMs = (nowNs - anchor) / 1_000_000L,
                audioMs = audioSamples.toLong() * 1000L / sampleRate,
            )
        }
    }

    fun onDecode(isFinal: Boolean, ms: Long, audioMs: Long, overlapped: Boolean) {
        synchronized(lock) {
            if (isFinal) {
                current.finalDecodeMs = ms
                current.finalDecodeAudioMs = audioMs
            } else {
                current.lastPartialDecodeMs = ms
                current.partialDecodeAudioMs = audioMs
                current.partialDecodes++
                if (overlapped) current.partialDecodesDuringGen++
            }
        }
    }

    /** A partial decode was dropped because a generation held the big cores. */
    fun onPartialSkipped() {
        synchronized(lock) {
            current.partialDecodesSkipped++
        }
    }

    fun onJoinerRelease(heldMs: Long) {
        synchronized(lock) {
            current.joinerHoldMs = heldMs
        }
    }

    fun onTranslateSubmitted(queuedBehind: Int) {
        synchronized(lock) {
            current.queuedBehind = queuedBehind
        }
    }

    fun onTranslateStarted(queueWaitMs: Long) {
        synchronized(lock) {
            current.queueWaitMs = queueWaitMs
        }
    }

    fun onTranslateFirstToken(ms: Long) {
        synchronized(lock) {
            current.firstTokenMs = ms
        }
    }

    fun onTranslateDone(genMs: Long, outputChars: Int) {
        synchronized(lock) {
            current.genMs = genMs
            current.outputChars = outputChars
            current.speechToDoneMs = (System.nanoTime() - current.anchorNs) / 1_000_000L
            archiveLocked()
        }
    }

    fun reset() {
        synchronized(lock) {
            completed.clear()
            current = LatencyTurn(seq = 0, startedAtMs = 0L)
            nextSeq = 1L
            _report.value = LatencyReport()
        }
        runCatching { file().writeText("") }
    }

    private fun archiveLocked() {
        val turn = current
        current = LatencyTurn(seq = 0, startedAtMs = 0L)
        if (!turn.hasAnything) return
        completed.addFirst(turn)
        while (completed.size > MAX_TURNS) completed.removeLast()
        _report.value = LatencyReport(turns = completed.toList())
        appendToFile(turn)
    }

    private fun appendToFile(turn: LatencyTurn) {
        runCatching {
            val target = file()
            if (target.length() > MAX_FILE_BYTES) {
                // Keep the tail; this file grows for the life of the install otherwise.
                val tail = target.readText().takeLast((MAX_FILE_BYTES / 2).toInt())
                target.writeText(tail.substringAfter('\n', tail))
            }
            target.appendText(turn.toLine() + "\n")
        }
    }

    private fun file(): File = File(context.filesDir, FILE_NAME)

    companion object {
        const val FILE_NAME = "latency-probe.txt"
        private const val MAX_TURNS = 40
        private const val MAX_FILE_BYTES = 256 * 1024L
    }
}

/**
 * One spoken turn, from the last speech window to the translation landing on screen.
 * A `-1` means "this stage never happened for this turn".
 */
data class LatencyTurn(
    val seq: Long,
    val startedAtMs: Long,
    val anchorNs: Long = 0L,
    var speechToEndpointMs: Long = -1L,
    var audioMs: Long = -1L,
    var finalDecodeMs: Long = -1L,
    var finalDecodeAudioMs: Long = -1L,
    var lastPartialDecodeMs: Long = -1L,
    var partialDecodeAudioMs: Long = -1L,
    var partialDecodes: Int = 0,
    var partialDecodesDuringGen: Int = 0,
    var partialDecodesSkipped: Int = 0,
    var joinerHoldMs: Long = -1L,
    var queuedBehind: Int = 0,
    var queueWaitMs: Long = -1L,
    var firstTokenMs: Long = -1L,
    var genMs: Long = -1L,
    var outputChars: Int = -1,
    var speechToDoneMs: Long = -1L,
) {
    val hasFinal: Boolean get() = finalDecodeMs >= 0L
    val hasAnything: Boolean get() = audioMs >= 0L || finalDecodeMs >= 0L || genMs >= 0L

    /** Real-time factor of the final decode: audio seconds processed per second of wall clock. */
    val finalRtf: Double
        get() = if (finalDecodeMs > 0L && finalDecodeAudioMs > 0L) {
            finalDecodeAudioMs.toDouble() / finalDecodeMs.toDouble()
        } else {
            0.0
        }

    fun toLine(): String = buildString {
        append(startedAtMs)
        append(" seq=").append(seq)
        append(" speech_to_endpoint=").append(speechToEndpointMs)
        append(" audio=").append(audioMs)
        append(" final_decode=").append(finalDecodeMs)
        append(" final_rtf=").append(String.format("%.2f", finalRtf))
        append(" partial_decode=").append(lastPartialDecodeMs)
        append(" partials=").append(partialDecodes)
        append(" partials_during_gen=").append(partialDecodesDuringGen)
        append(" partials_skipped=").append(partialDecodesSkipped)
        append(" joiner_hold=").append(joinerHoldMs)
        append(" queue_wait=").append(queueWaitMs)
        append(" queued_behind=").append(queuedBehind)
        append(" first_token=").append(firstTokenMs)
        append(" gen=").append(genMs)
        append(" out_chars=").append(outputChars)
        append(" speech_to_done=").append(speechToDoneMs)
    }
}

data class LatencyReport(
    val turns: List<LatencyTurn> = emptyList(),
) {
    private fun values(pick: (LatencyTurn) -> Long): List<Long> =
        turns.map(pick).filter { it >= 0L }

    /**
     * Median of each stage over the recorded turns. Medians rather than means: one
     * thermally-throttled outlier should not move the number you are optimising.
     */
    val medians: Map<String, Long>
        get() = linkedMapOf(
            "speech→vad endpoint" to median { it.speechToEndpointMs },
            "final decode (ASR)" to median { it.finalDecodeMs },
            "joiner hold" to median { it.joinerHoldMs },
            "queue wait" to median { it.queueWaitMs },
            "first token" to median { it.firstTokenMs },
            "generation" to median { it.genMs },
            "speech→translated" to median { it.speechToDoneMs },
        ).filterValues { it >= 0L }

    private fun median(pick: (LatencyTurn) -> Long): Long {
        val sorted = values(pick).sorted()
        if (sorted.isEmpty()) return -1L
        return sorted[sorted.size / 2]
    }

    val overlappedPartials: Int get() = turns.sumOf { it.partialDecodesDuringGen }
    val skippedPartials: Int get() = turns.sumOf { it.partialDecodesSkipped }
}
