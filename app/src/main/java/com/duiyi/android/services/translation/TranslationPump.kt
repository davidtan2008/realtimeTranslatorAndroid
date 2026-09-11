package com.duiyi.android.services.translation

import com.duiyi.android.domain.Translating
import com.duiyi.android.domain.TranslationJob
import com.duiyi.android.services.DuiyiLog
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslationPump(
    private val translator: Translating,
    scope: CoroutineScope,
    private val onResult: (TranslationJob, String) -> Unit,
) {
    private val wake = Channel<Unit>(Channel.UNLIMITED)
    private val lock = Any()
    private val pending = LinkedHashMap<UUID, TranslationJob>()

    init {
        scope.launch(Dispatchers.Default) {
            for (ignored in wake) {
                while (true) {
                    val job = nextJob() ?: break
                    DuiyiLog.i(
                        "translate run ${job.from.translateTag}->${job.to.translateTag} len=${job.text.length} final=${job.isFinal}",
                    )
                    val text = try {
                        translator.translate(job.from, job.to, job.text, job.previousSource)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        DuiyiLog.w(
                            "translate failed ${job.from.translateTag}->${job.to.translateTag}",
                            error,
                        )
                        continue
                    }
                    if (text.isBlank()) {
                        DuiyiLog.w("translate empty ${job.from.translateTag}->${job.to.translateTag} src=${job.text.take(40)}")
                        continue
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
            }
        }
    }

    fun submit(job: TranslationJob) {
        if (job.from == job.to) {
            DuiyiLog.i("translate skip same-language ${job.from.translateTag} len=${job.text.length}")
            onResult(job, job.text)
            return
        }
        synchronized(lock) {
            pending[job.utteranceId] = job
        }
        val sent = wake.trySend(Unit)
        DuiyiLog.i(
            "translate submit ${job.from.translateTag}->${job.to.translateTag} queued=${pendingSize()} sent=${sent.isSuccess} final=${job.isFinal}",
        )
    }

    private fun pendingSize(): Int = synchronized(lock) { pending.size }

    private fun nextJob(): TranslationJob? = synchronized(lock) {
        val iterator = pending.entries.iterator()
        if (!iterator.hasNext()) return null
        val entry = iterator.next()
        iterator.remove()
        entry.value
    }
}
