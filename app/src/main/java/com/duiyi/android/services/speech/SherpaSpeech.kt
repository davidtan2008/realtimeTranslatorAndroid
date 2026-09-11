package com.duiyi.android.services.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.DuiyiError
import com.duiyi.android.domain.SpeechRecognitionStyle
import com.duiyi.android.domain.SpokenText
import com.duiyi.android.domain.Utterance
import com.duiyi.android.domain.routing.SpokenSegmentJoiner
import com.duiyi.android.domain.routing.UtteranceAssembler
import com.duiyi.android.services.DuiyiLog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class SherpaSpeech @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: SherpaModelStore,
) {
    private val modelLock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var recognizerLanguage = ""

    @Volatile
    private var running = false

    @Volatile
    private var generation = 0

    @Volatile
    private var record: AudioRecord? = null

    suspend fun prepare() {
        models.ensureReady()
        withContext(Dispatchers.Default) {
            obtainRecognizer()
        }
    }

    fun stop() {
        running = false
        generation += 1
        try {
            record?.stop()
        } catch (_: Exception) {
        }
    }

    fun captions(
        languages: List<AppLanguage>,
        fallback: AppLanguage,
        style: SpeechRecognitionStyle,
    ): Flow<Utterance> = callbackFlow {
        val token = ++generation
        running = true
        val assembler = UtteranceAssembler()
        val joiner = SpokenSegmentJoiner()
        var detected: AppLanguage? = null
        val primary = languages.firstOrNull() ?: fallback
        val bilingual = languages.size > 1 && style == SpeechRecognitionStyle.LiveCaptions

        fun sendPiece(piece: Utterance) {
            val utterance = assembler.ingest(piece.language, piece.text, piece.isFinal)
            trySend(utterance ?: piece)
        }

        fun emitAssembled(language: AppLanguage, text: String, isFinal: Boolean) {
            val normalized = SpokenText.normalizeAsr(text, language)
            if (normalized.isBlank()) return
            if (!bilingual && !SpokenText.matchesPrimaryScript(normalized, primary)) {
                DuiyiLog.i("sherpa drop mismatched script primary=$primary text=$normalized")
                return
            }
            val inferred = SpeechTextRouter.languageFor(
                text = normalized,
                bilingual = bilingual,
                detected = detected,
                primary = primary,
                claimed = language,
            )
            if (bilingual && SpokenText.scriptIsDecisive(normalized) && detected != null && inferred != detected) {
                joiner.forceFlush()?.let { sendPiece(it) }
                assembler.silenceTimedOut()?.let { trySend(it) }
                assembler.startNewUtterance()
            }
            if (detected == null || (bilingual && SpokenText.scriptIsDecisive(normalized))) {
                detected = inferred
            }
            joiner.ingest(System.nanoTime(), inferred, normalized, isFinal).forEach(::sendPiece)
        }

        val job = launch(Dispatchers.Default) {
            var vad: Vad? = null
            try {
                val rec = obtainRecognizer(senseVoiceLanguage(languages, bilingual))
                vad = createVad()
                val mic = openMicrophone()
                record = mic
                mic.startRecording()
                DuiyiLog.i("sherpa recording source=${mic.audioSource} style=$style engine=sensevoice")
                SpeechProbe.i(context, "sherpa recording style=$style engine=sensevoice")

                val finals = Channel<FloatArray>(Channel.UNLIMITED)
                val partials = Channel<FloatArray>(Channel.CONFLATED)
                val decodeJob = launch {
                    try {
                        while (running && generation == token) {
                            val queued = finals.tryReceive().getOrNull()
                            val (samples, isFinal) = if (queued != null) {
                                queued to true
                            } else {
                                val partial = partials.tryReceive().getOrNull()
                                if (partial != null) {
                                    partial to false
                                } else {
                                    select {
                                        finals.onReceive { it to true }
                                        partials.onReceive { it to false }
                                    }
                                }
                            }
                            if (!running || generation != token) break
                            val decoded = decodeSamples(rec, samples, primary)
                            if (decoded.first.isNotBlank()) {
                                emitAssembled(decoded.second, decoded.first, isFinal)
                            }
                            joiner.tick(System.nanoTime())?.let(::sendPiece)
                        }
                    } catch (_: ClosedReceiveChannelException) {
                    }
                }
                consumeMic(mic, vad, finals, partials, token)
                decodeJob.join()
                joiner.forceFlush()?.let(::sendPiece)
            } catch (error: Exception) {
                DuiyiLog.w("sherpa loop failed", error)
                SpeechProbe.i(context, "sherpa loop failed ${error.message}")
                close(
                    error as? SpeechEngineException
                        ?: SpeechEngineException(DuiyiError.SpeechEngineSilent),
                )
            } finally {
                try {
                    vad?.release()
                } catch (_: Exception) {
                }
                try {
                    record?.stop()
                } catch (_: Exception) {
                }
                try {
                    record?.release()
                } catch (_: Exception) {
                }
                record = null
            }
        }

        awaitClose {
            running = false
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            job.cancel()
        }
    }

    private suspend fun consumeMic(
        mic: AudioRecord,
        vad: Vad,
        finals: Channel<FloatArray>,
        partials: Channel<FloatArray>,
        token: Int,
    ) {
        val shortBuf = ShortArray(SAMPLE_RATE / 10)
        val window = FloatArray(WINDOW)
        val pending = FloatArray(WINDOW)
        var pendingN = 0
        val speech = SampleAccumulator()
        var lastPartialAt = 0L
        try {
            while (running && generation == token) {
            val n = try {
                mic.read(shortBuf, 0, shortBuf.size)
            } catch (error: Exception) {
                DuiyiLog.w("sherpa read failed", error)
                break
            }
            if (n <= 0) continue
            var offset = 0
            while (offset < n) {
                val take = minOf(WINDOW - pendingN, n - offset)
                for (i in 0 until take) {
                    pending[pendingN + i] = shortBuf[offset + i] / 32768.0f
                }
                pendingN += take
                offset += take
                if (pendingN < WINDOW) continue
                pending.copyInto(window)
                pendingN = 0
                try {
                    vad.acceptWaveform(window)
                } catch (error: Exception) {
                    DuiyiLog.w("sherpa vad failed", error)
                    continue
                }
                val speaking = try {
                    vad.isSpeechDetected()
                } catch (_: Exception) {
                    false
                }
                if (speaking) {
                    speech.append(window)
                    val now = System.nanoTime()
                    if (speech.size >= PARTIAL_SAMPLES && now - lastPartialAt >= PARTIAL_NS) {
                        lastPartialAt = now
                        partials.trySend(speech.snapshot())
                    }
                }
                while (true) {
                    val empty = try {
                        vad.empty()
                    } catch (_: Exception) {
                        true
                    }
                    if (empty) break
                    val segment = try {
                        vad.front().also { vad.pop() }
                    } catch (error: Exception) {
                        DuiyiLog.w("sherpa vad pop failed", error)
                        break
                    }
                    speech.clear()
                    lastPartialAt = System.nanoTime()
                    DuiyiLog.i("sherpa vad segment samples=${segment.samples.size}")
                    finals.send(segment.samples)
                }
            }
        }
        try {
            vad.flush()
            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                if (segment.samples.isNotEmpty()) finals.send(segment.samples)
            }
        } catch (error: Exception) {
            DuiyiLog.w("sherpa vad flush failed", error)
        }
        if (speech.size >= WINDOW) {
            finals.send(speech.snapshot())
        }
        speech.clear()
    } finally {
        finals.close()
        partials.close()
    }
}

    private fun decodeSamples(
        rec: OfflineRecognizer,
        samples: FloatArray,
        fallback: AppLanguage,
    ): Pair<String, AppLanguage> {
        if (samples.size < WINDOW) return "" to fallback
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val text = SenseVoiceText.strip(result.text)
            val language = SenseVoiceText.languageFrom(result.lang, text, fallback)
            DuiyiLog.i("sherpa decode lang=${result.lang} len=${text.length}")
            text to language
        } catch (error: Exception) {
            DuiyiLog.w("sherpa decode failed", error)
            "" to fallback
        } finally {
            try {
                stream.release()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun obtainRecognizer(languageHint: String = ""): OfflineRecognizer = modelLock.withLock {
        if (!models.isReady()) {
            recognizer?.release()
            recognizer = null
            recognizerLanguage = ""
            throw SpeechEngineException(DuiyiError.SpeechModelsMissing)
        }
        val current = recognizer
        if (current != null && recognizerLanguage == languageHint) return current
        current?.release()
        recognizer = createRecognizer(languageHint).also { recognizerLanguage = languageHint }
        recognizer!!
    }

    private fun createRecognizer(languageHint: String): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = models.modelPath(),
                    language = languageHint,
                    useInverseTextNormalization = true,
                ),
                tokens = models.tokensPath(),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                debug = false,
                provider = "cpu",
                modelType = "sense_voice",
            ),
            decodingMethod = "greedy_search",
        )
        DuiyiLog.i("sherpa creating sensevoice recognizer lang=$languageHint")
        SpeechProbe.i(context, "sherpa creating sensevoice recognizer lang=$languageHint")
        return OfflineRecognizer(assetManager = null, config = config)
    }

    private fun senseVoiceLanguage(languages: List<AppLanguage>, bilingual: Boolean): String {
        if (bilingual) return ""
        return when (languages.firstOrNull()) {
            AppLanguage.ZhHans -> "zh"
            AppLanguage.English -> "en"
            AppLanguage.Japanese -> "ja"
            AppLanguage.Korean -> "ko"
            else -> ""
        }
    }

    private fun createVad(): Vad {
        return Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = models.vadPath(),
                    threshold = 0.5f,
                    minSilenceDuration = 0.55f,
                    minSpeechDuration = 0.35f,
                    windowSize = WINDOW,
                    maxSpeechDuration = 20.0f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private fun openMicrophone(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) throw SpeechEngineException(DuiyiError.MicrophoneDenied)
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
        )
        for (source in sources) {
            val created = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    min * 4,
                )
            } catch (_: Exception) {
                null
            }
            if (created?.state == AudioRecord.STATE_INITIALIZED) {
                return created
            }
            created?.release()
        }
        throw SpeechEngineException(DuiyiError.MicrophoneDenied)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW = 512
        private const val PARTIAL_SAMPLES = SAMPLE_RATE
        private const val PARTIAL_NS = 1_000_000_000L
    }
}

private class SampleAccumulator {
    private var data = FloatArray(SAMPLE_CAPACITY)
    var size: Int = 0
        private set

    fun append(chunk: FloatArray) {
        if (size >= SAMPLE_CAPACITY) return
        val take = minOf(chunk.size, SAMPLE_CAPACITY - size)
        ensure(size + take)
        chunk.copyInto(data, size, 0, take)
        size += take
    }

    fun snapshot(): FloatArray = data.copyOf(size)

    fun clear() {
        size = 0
    }

    private fun ensure(needed: Int) {
        if (needed <= data.size) return
        var cap = data.size
        while (cap < needed) cap *= 2
        data = data.copyOf(cap)
    }

    companion object {
        private const val SAMPLE_CAPACITY = 16_000 * 8
    }
}
