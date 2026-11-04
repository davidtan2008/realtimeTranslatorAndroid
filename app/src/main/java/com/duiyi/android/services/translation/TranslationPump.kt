package com.duiyi.android.services.translation

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.Translating
import com.duiyi.android.domain.TranslationJob
import com.duiyi.android.services.ComputeGate
import com.duiyi.android.services.DuiyiLog
import com.duiyi.android.services.LatencyProbe
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-worker queue in front of [Translating].
 *
 * Jobs run one at a time and an in-flight generate is never cancelled — evicting a
 * half-finished translation is the mistake this pipeline exists to avoid. Re-submitting
 * the same utterance overwrites the *pending* entry instead, so a queued job that has
 * not started yet is cheap to supersede.
 */
class TranslationPump(
    private val translator: Translating,
    private val scope: CoroutineScope,
    private val probe: LatencyProbe,
    private val gate: ComputeGate,
    private val onResult: (TranslationJob, String) -> Unit,
    private val onProgress: (TranslationJob, String) -> Unit = { _, _ -> },
) {
    private val wake = Channel<Unit>(Channel.UNLIMITED)
    private val lock = Any()
    private val pending = LinkedHashMap<UUID, TranslationJob>()

    init {
        scope.launch(Dispatchers.Default) {
            for (ignored in wake) {
                while (true) {
                    val job = nextJob() ?: break
                    run(job)
                }
            }
        }
    }

    private suspend fun run(job: TranslationJob) {
        val startedNs = System.nanoTime()
        val queueWaitMs = if (job.submittedNs > 0L && startedNs >= job.submittedNs) {
            (startedNs - job.submittedNs) / 1_000_000L
        } else {
            -1L
        }
        probe.onTranslateStarted(queueWaitMs)
        DuiyiLog.i(
            "translate run ${job.from.translateTag}->${job.to.translateTag} len=${job.text.length} " +
                "final=${job.isFinal} queue=${queueWaitMs}ms",
        )
        val text = try {
            coroutineScope {
                val streamer = launch { streamProgress(job, startedNs) }
                try {
                    translator.translate(job.from, job.to, job.text, job.previousSource)
                } finally {
                    streamer.cancel()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DuiyiLog.w("translate failed ${job.from.translateTag}->${job.to.translateTag}", error)
            return
        }
        probe.onTranslateDone((System.nanoTime() - startedNs) / 1_000_000L, text.length)
        if (text.isBlank()) {
            DuiyiLog.w("translate empty ${job.from.translateTag}->${job.to.translateTag} src=${job.text.take(40)}")
            return
        }
        DuiyiLog.i(
            "translate ok ${job.from.translateTag}->${job.to.translateTag} in=${job.text.length} out=${text.length} preview=${text.take(24)}",
        )
        try {
            withContext(Dispatchers.Main.immediate) {
                onResult(job, text)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DuiyiLog.w("translate apply failed", error)
        }
    }

    /**
     * Generation takes over a second, so showing the translation only once it is complete
     * leaves the reader staring at the source line. This mirrors the growing text out to
     * the UI. It deliberately does not touch the transcript or the store — only the final
     * result may be persisted.
     */
    private suspend fun streamProgress(job: TranslationJob, startedNs: Long) {
        var reportedFirstToken = false
        // Cancellation arrives through delay(); no explicit isActive check needed.
        while (true) {
            delay(POLL_MS)
            val raw = translator.inProgressText()
            if (raw.isEmpty()) continue
            val text = TranslateGemmaPrompt.sanitize(raw)
            if (text.isEmpty()) continue
            if (!reportedFirstToken) {
                reportedFirstToken = true
                probe.onTranslateFirstToken((System.nanoTime() - startedNs) / 1_000_000L)
            }
            try {
                withContext(Dispatchers.Main.immediate) {
                    onProgress(job, text)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DuiyiLog.w("translate progress apply failed", error)
            }
        }
    }

    fun submit(job: TranslationJob) {
        if (job.from == job.to) {
            DuiyiLog.i("translate skip same-language ${job.from.translateTag} len=${job.text.length}")
            onResult(job, job.text)
            return
        }
        val stamped = if (job.submittedNs == 0L) {
            job.copy(submittedNs = System.nanoTime())
        } else {
            job
        }
        synchronized(lock) {
            pending[job.utteranceId] = stamped
        }
        val sent = wake.trySend(Unit)
        val queuedBehind = pendingSize() - 1
        probe.onTranslateSubmitted(queuedBehind.coerceAtLeast(0))
        DuiyiLog.i(
            "translate submit ${job.from.translateTag}->${job.to.translateTag} queued=${pendingSize()} sent=${sent.isSuccess} final=${job.isFinal}",
        )
    }

    /** True when something is queued or generating, so callers can skip speculative work. */
    val busy: Boolean
        get() = pendingSize() > 0 || gate.translationInFlight

    /**
     * Warm the KV cache for a source sentence that is not being translated yet. Skipped
     * whenever a real translation is queued or in flight — speculation must never slow
     * down the thing the user is actually waiting for.
     */
    suspend fun speculate(from: AppLanguage, to: AppLanguage, text: String) {
        if (text.isBlank() || from == to) return
        if (busy) return
        runCatching { translator.prefillSource(from, to, text) }
            .onFailure { DuiyiLog.w("translate speculate failed", it) }
    }

    private fun pendingSize(): Int = synchronized(lock) { pending.size }

    private fun nextJob(): TranslationJob? = synchronized(lock) {
        val iterator = pending.entries.iterator()
        if (!iterator.hasNext()) return null
        val entry = iterator.next()
        iterator.remove()
        entry.value
    }

    companion object {
        private const val POLL_MS = 120L
    }
}
