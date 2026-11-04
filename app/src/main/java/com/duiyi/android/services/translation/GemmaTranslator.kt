package com.duiyi.android.services.translation

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.DuiyiError
import com.duiyi.android.domain.Translating
import com.duiyi.android.domain.TranslationPairStatus
import com.duiyi.android.services.ComputeGate
import com.duiyi.android.services.DuiyiLog
import com.duiyi.android.services.speech.SpeechEngineException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class GemmaTranslator @Inject constructor(
    private val models: GemmaModelStore,
    private val gate: ComputeGate,
) : Translating {
    private val lock = Mutex()
    @Volatile private var handle: Long = 0L

    override suspend fun pairStatus(from: AppLanguage, to: AppLanguage): TranslationPairStatus {
        if (from == to) return TranslationPairStatus.Unsupported
        return if (models.isReady()) TranslationPairStatus.Installed else TranslationPairStatus.Supported
    }

    override suspend fun prepare(
        pairs: List<Pair<AppLanguage, AppLanguage>>,
        onProgress: (Float) -> Unit,
    ) {
        models.ensureReady { value -> onProgress((value * 0.97f).coerceIn(0f, 0.97f)) }
        ensureLoaded()
        withContext(Dispatchers.Default) {
            lock.withLock {
                if (handle == 0L) return@withLock
                pairs.distinct().forEach { (from, to) ->
                    if (from == to) return@forEach
                    DuiyiLog.i("gemma warm prefix ${from.translateTag}->${to.translateTag}")
                    LlamaNative.warmPrefix(handle, TranslateGemmaPrompt.prefix(from, to))
                }
            }
        }
        onProgress(1f)
    }

    override suspend fun translate(
        from: AppLanguage,
        to: AppLanguage,
        text: String,
        previousSource: String,
    ): String {
        if (from == to || text.isBlank()) return text
        if (!models.isReady()) throw SpeechEngineException(DuiyiError.ModelsMissing)
        ensureLoaded()
        val split = TranslateGemmaPrompt.parts(from, to, text, previousSource)
        DuiyiLog.i("gemma generate ${from.translateTag}->${to.translateTag} prefix=${split.prefix.length} suffix=${split.suffix.length} src=${text.take(40)}")
        val raw = withContext(Dispatchers.Default) {
            lock.withLock {
                if (handle == 0L) throw SpeechEngineException(DuiyiError.ModelsMissing)
                val current = handle
                // Tell the speech path to stop re-decoding partials for the live line:
                // it shares these cores and would stretch the generation.
                gate.beginTranslation()
                try {
                    coroutineScope {
                        val timeout = launch {
                            delay(180_000L)
                            DuiyiLog.w("gemma generate timed out ${from.translateTag}->${to.translateTag}")
                            LlamaNative.abort(current)
                        }
                        try {
                            LlamaNative.generate(
                                current,
                                split.prefix,
                                split.suffix,
                                TranslateGemmaPrompt.maxOutputTokens(text),
                            )
                        } finally {
                            timeout.cancel()
                        }
                    }
                } finally {
                    gate.endTranslation()
                }
            }
        }
        val cleaned = TranslateGemmaPrompt.sanitize(raw)
        if (cleaned.isBlank()) {
            DuiyiLog.w("gemma empty translation from=${from.translateTag} to=${to.translateTag}")
        }
        return cleaned
    }

    /**
     * Decode template + source into KV and snapshot it, without generating. Cheap enough
     * to run on a partial hypothesis; the snapshot is simply discarded if the final text
     * turns out to differ.
     */
    override suspend fun prefillSource(from: AppLanguage, to: AppLanguage, text: String) {
        if (from == to || text.isBlank() || !models.isReady()) return
        ensureLoaded()
        val split = TranslateGemmaPrompt.parts(from, to, text.trim())
        withContext(Dispatchers.Default) {
            lock.withLock {
                val current = handle
                if (current == 0L) return@withLock
                LlamaNative.prefillSource(current, split.prefix, split.suffix)
            }
        }
    }

    override fun inProgressText(): String {
        val current = handle
        if (current == 0L) return ""
        return runCatching { LlamaNative.progress(current) }.getOrDefault("")
    }

    override suspend fun installedLanguages(): Set<AppLanguage> =
        if (models.isReady()) AppLanguage.entries.toSet() else emptySet()

    override suspend fun downloadLanguage(language: AppLanguage) {
        download()
    }

    override suspend fun download(onProgress: (Float) -> Unit) {
        models.ensureReady(onProgress)
    }

    override suspend fun release() {
        val current = handle
        if (current != 0L) LlamaNative.abort(current)
        unload()
    }

    override suspend fun deleteLanguage(language: AppLanguage) {
        release()
        models.deleteAll()
    }

    private suspend fun ensureLoaded() {
        if (handle != 0L) return
        lock.withLock {
            if (handle != 0L) return
            if (!models.isReady()) throw SpeechEngineException(DuiyiError.ModelsMissing)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            handle = withContext(Dispatchers.Default) {
                try {
                    DuiyiLog.i("gemma native create ${models.modelPath()}")
                    LlamaNative.create(models.modelPath(), N_CTX, threads)
                } catch (error: Throwable) {
                    DuiyiLog.w("gemma native library missing", error)
                    0L
                }
            }
            if (handle == 0L) {
                DuiyiLog.w("gemma failed to load ${models.modelPath()}")
                throw SpeechEngineException(DuiyiError.ModelsMissing)
            }
            DuiyiLog.i("gemma loaded threads=$threads")
        }
    }

    private suspend fun unload() {
        lock.withLock {
            val current = handle
            if (current != 0L) {
                // Publish the teardown before freeing, so a concurrent inProgressText()
                // bails out on 0 instead of reading a freed handle.
                handle = 0L
                LlamaNative.destroy(current)
            }
        }
    }

    companion object {
        private const val N_CTX = 1024
    }
}
