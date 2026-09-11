package com.duiyi.android.services.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.DuiyiError
import com.duiyi.android.domain.SpeechRecognitionStyle
import com.duiyi.android.domain.SpeechRecognizing
import com.duiyi.android.domain.Utterance
import com.duiyi.android.domain.routing.UtteranceAssembler
import com.duiyi.android.services.DuiyiLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class AndroidSpeechEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intentBridge: SpeechIntentBridge,
    private val sherpa: SherpaSpeech,
) : SpeechRecognizing {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var generation = 0
    private var restartCount = 0

    override suspend fun start(
        languages: List<AppLanguage>,
        fallback: AppLanguage,
        style: SpeechRecognitionStyle,
    ): Flow<Utterance> {
        stopInternal()
        val honor = SpeechEnginePolicy.isHonorFamily(Build.MANUFACTURER, Build.BRAND)
        if (honor && SpeechEnginePolicy.useSherpaOnDevice(honor)) {
            DuiyiLog.i("speech start honor=true engine=sherpa")
            SpeechProbe.i(context, "sherpa start")
            sherpa.prepare()
            return sherpa.captions(languages, fallback, style)
        }
        val allowOnDevice = !honor && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val languageSwitch = SpeechEnginePolicy.enableLanguageSwitch(Build.MANUFACTURER, Build.BRAND)
        val googleApp = SpeechRecognizerFactory.hasGoogleApp(context)
        val googleMic = SpeechRecognizerFactory.googleMicGranted(context)
        val vendorDefault = honor && SpeechRecognizerFactory.defaultIsVendorMute(context)
        val defaultService = SpeechRecognizerFactory.defaultRecognitionService(context)
        val candidates = SpeechRecognizerFactory.candidates(context, honor, allowOnDevice)
        DuiyiLog.i(
            "speech start honor=$honor googleApp=$googleApp googleMic=$googleMic " +
                "vendorDefault=$vendorDefault default=$defaultService " +
                "candidates=${candidates.map { it.source }} " +
                "services=${SpeechRecognizerFactory.listedRecognitionServices(context)}",
        )
        if (googleApp && !googleMic) {
            throw SpeechEngineException(DuiyiError.GoogleMicrophoneDenied)
        }
        if (candidates.isEmpty()) {
            throw SpeechEngineException(
                if (vendorDefault) DuiyiError.HonorRecognizerBlocked else DuiyiError.SpeechEngineSilent,
            )
        }
        return callbackFlow {
            val token = ++generation
            running = true
            restartCount = 0
            val assembler = UtteranceAssembler()
            var detected: AppLanguage? = null
            val primary = languages.firstOrNull() ?: fallback
            val bilingual = languages.size > 1 && style == SpeechRecognitionStyle.LiveCaptions
            var gotReady = false
            var candidateIndex = 0
            var watchdogRetries = 0
            var currentSource = ""
            var preferOffline = false

            fun emitAssembled(language: AppLanguage, text: String, isFinal: Boolean) {
                val inferred = SpeechTextRouter.languageFor(
                    text = text,
                    bilingual = bilingual,
                    detected = detected,
                    primary = primary,
                    claimed = language,
                )
                val utterance = assembler.ingest(inferred, text, isFinal)
                if (utterance != null) {
                    trySend(utterance)
                    return
                }
                DuiyiLog.w("speech assembler dropped claimed=$language inferred=$inferred text=$text")
                trySend(
                    Utterance(
                        id = java.util.UUID.randomUUID(),
                        language = inferred,
                        text = text,
                        isFinal = isFinal,
                    ),
                )
            }

            fun silentFailure(): SpeechEngineException =
                SpeechEngineException(
                    if (vendorDefault) DuiyiError.HonorRecognizerBlocked else DuiyiError.SpeechEngineSilent,
                )

            fun listen(engine: SpeechRecognizer?) {
                startListening(
                    engine = engine,
                    languages = languages,
                    primary = primary,
                    bilingual = bilingual,
                    style = style,
                    preferOffline = preferOffline,
                    languageSwitch = languageSwitch,
                    honor = honor,
                )
            }

            fun destroyRecognizer() {
                try {
                    recognizer?.stopListening()
                    recognizer?.cancel()
                    recognizer?.destroy()
                } catch (_: Exception) {
                }
                recognizer = null
            }

            lateinit var watchdog: Runnable
            lateinit var listener: RecognitionListener

            fun scheduleWatchdog() {
                main.removeCallbacks(watchdog)
                main.postDelayed(watchdog, if (honor) 8_000 else 2_500)
            }

            fun bindAt(index: Int): Boolean {
                destroyRecognizer()
                if (index >= candidates.size) return false
                val candidate = candidates[index]
                val binding = SpeechRecognizerFactory.create(context, candidate, preferOffline = false)
                if (binding == null) return false
                preferOffline = binding.preferOffline
                currentSource = binding.source
                recognizer = binding.engine
                DuiyiLog.i("speech bound source=${binding.source}")
                SpeechProbe.i(context, "recognizer bound source=${binding.source}")
                return true
            }

            fun advanceEngine(reason: String): Boolean {
                var next = candidateIndex + 1
                while (next < candidates.size) {
                    DuiyiLog.w("speech switching engine after $reason → ${candidates[next].source}")
                    if (bindAt(next)) {
                        candidateIndex = next
                        recognizer?.setRecognitionListener(listener)
                        listen(recognizer)
                        scheduleWatchdog()
                        return true
                    }
                    next += 1
                }
                return false
            }

            listener = object : RecognitionListener {
                private fun alive(): Boolean = running && generation == token

                override fun onReadyForSpeech(params: Bundle?) {
                    if (!alive()) return
                    gotReady = true
                    restartCount = 0
                    main.removeCallbacks(watchdog)
                    DuiyiLog.i("speech ready source=$currentSource")
                    SpeechProbe.i(context, "recognizer ready source=$currentSource")
                }

                override fun onBeginningOfSpeech() {
                    if (!alive()) return
                    gotReady = true
                    DuiyiLog.i("speech beginning")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (!alive()) return
                    if (rmsdB > 2f && !gotReady) {
                        gotReady = true
                        main.removeCallbacks(watchdog)
                        DuiyiLog.i("speech rms=$rmsdB source=$currentSource")
                    }
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (alive()) DuiyiLog.i("speech endOfSpeech")
                }

                override fun onError(error: Int) {
                    if (!alive()) return
                    DuiyiLog.w("speech error=${errorName(error)} ($error) source=$currentSource")
                    SpeechProbe.i(context, "recognizer error=${errorName(error)} source=$currentSource")
                    val retryable = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_SERVER ||
                        error == SpeechRecognizer.ERROR_NETWORK ||
                        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    val switchNow = !gotReady && (
                        error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_SERVER ||
                            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                        )
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && googleApp) {
                        close(SpeechEngineException(DuiyiError.GoogleMicrophoneDenied))
                        return
                    }
                    if (switchNow && advanceEngine(errorName(error))) return
                    if (honor && !gotReady && vendorDefault) {
                        close(SpeechEngineException(DuiyiError.HonorRecognizerBlocked))
                        return
                    }
                    if (retryable && restartCount < 10) {
                        restartCount += 1
                        main.postDelayed({
                            if (alive()) listen(recognizer)
                        }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 450 else 280)
                    } else {
                        close(silentFailure())
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (!alive()) return
                    gotReady = true
                    main.removeCallbacks(watchdog)
                    val text = bestText(results)
                    DuiyiLog.i("speech results len=${text.length} keys=${results?.keySet()} source=$currentSource")
                    SpeechProbe.i(context, "recognizer results len=${text.length} source=$currentSource")
                    val lang = languageFrom(results, detected, primary)
                    if (text.isNotBlank()) emitAssembled(lang, text, true)
                    assembler.silenceTimedOut()?.let { trySend(it) }
                    if (alive()) {
                        main.post { if (alive()) listen(recognizer) }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!alive()) return
                    gotReady = true
                    main.removeCallbacks(watchdog)
                    val text = bestText(partialResults)
                    if (text.isNotBlank()) {
                        DuiyiLog.i("speech partial len=${text.length} source=$currentSource")
                    }
                    val lang = languageFrom(partialResults, detected, primary)
                    if (text.isNotBlank()) emitAssembled(lang, text, false)
                }

                override fun onSegmentResults(segmentResults: Bundle) {
                    if (!alive()) return
                    gotReady = true
                    main.removeCallbacks(watchdog)
                    val text = bestText(segmentResults)
                    val lang = languageFrom(segmentResults, detected, primary)
                    if (text.isNotBlank()) emitAssembled(lang, text, false)
                }

                override fun onEndOfSegmentedSession() {
                    if (!alive()) return
                    assembler.silenceTimedOut()?.let { trySend(it) }
                    main.post { if (alive()) listen(recognizer) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onLanguageDetection(results: Bundle) {
                    if (!alive()) return
                    val tag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: return
                    AppLanguage.fromSpeechLocale(tag)?.let { detected = it }
                }
            }

            watchdog = Runnable {
                if (!running || generation != token || gotReady) return@Runnable
                DuiyiLog.w("speech watchdog: no onReadyForSpeech source=$currentSource retry=$watchdogRetries")
                SpeechProbe.i(context, "recognizer watchdog source=$currentSource retry=$watchdogRetries")
                if (watchdogRetries < if (honor) 3 else 1) {
                    watchdogRetries += 1
                    bindAt(candidateIndex)
                    recognizer?.setRecognitionListener(listener)
                    listen(recognizer)
                    scheduleWatchdog()
                    return@Runnable
                }
                close(silentFailure())
            }

            suspend fun emitUiText(result: androidx.activity.result.ActivityResult?) {
                var text = HonorGoogleSpeech.bestText(result?.data)
                if (text.isBlank()) text = intentBridge.consumeBroadcastText().orEmpty()
                SpeechProbe.i(
                    context,
                    "honor ui result keys=${HonorGoogleSpeech.extraKeys(result?.data)} textLen=${text.length}",
                )
                if (text.isBlank()) return
                emitAssembled(primary, text, true)
            }

            suspend fun runHonorUiOnce() {
                val intents = HonorGoogleSpeech.uiIntents(context)
                SpeechProbe.i(context, "honor ui candidates=${intents.map(HonorGoogleSpeech::describe)}")
                for (intent in intents) {
                    val (shown, result) = intentBridge.showAndWait(intent)
                    SpeechProbe.i(context, "honor ui ${HonorGoogleSpeech.describe(intent)} shown=$shown")
                    if (!shown) continue
                    emitUiText(result)
                    return
                }
            }

            val startJob = launch(Dispatchers.Main) {
                if (!running || generation != token) return@launch
                if (honor) intentBridge.sessionActive = true
                if (honor && googleApp && SpeechEnginePolicy.useGoogleSpeechIntent(honor)) {
                    runHonorUiOnce()
                    SpeechProbe.i(context, "after honor ui; recognizer")
                    delay(500)
                }
                try {
                    if (!bindAt(0)) {
                        if (!advanceEngine("create-failed")) {
                            close(silentFailure())
                        }
                        return@launch
                    }
                    recognizer?.setRecognitionListener(listener)
                    listen(recognizer)
                    scheduleWatchdog()
                } catch (error: Exception) {
                    DuiyiLog.w("speech bind failed", error)
                    SpeechProbe.i(context, "recognizer bind failed ${error.message}")
                    close(silentFailure())
                }
            }

            awaitClose {
                startJob.cancel()
                intentBridge.sessionActive = false
                intentBridge.cancel()
                main.removeCallbacks(watchdog)
                main.post {
                    if (generation == token) {
                        running = false
                        generation += 1
                        destroyRecognizerNow()
                    }
                }
            }
        }
    }

    override suspend fun stop() = stopInternal()

    private fun destroyRecognizerNow() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private suspend fun stopInternal() {
        running = false
        generation += 1
        sherpa.stop()
        intentBridge.sessionActive = false
        intentBridge.cancel()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyRecognizerNow()
            return
        }
        suspendCancellableCoroutine { cont ->
            main.post {
                destroyRecognizerNow()
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private fun startListening(
        engine: SpeechRecognizer?,
        languages: List<AppLanguage>,
        primary: AppLanguage,
        bilingual: Boolean,
        style: SpeechRecognitionStyle,
        preferOffline: Boolean,
        languageSwitch: Boolean,
        honor: Boolean,
    ) {
        val recognizer = engine ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary.bcp47)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary.bcp47)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (SpeechEnginePolicy.useSilenceWindowExtras(honor)) {
                val silence = if (style == SpeechRecognitionStyle.MeetingNotes) 1400 else 1100
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            }
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            if (bilingual && languageSwitch) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    ArrayList(languages.map { it.bcp47 }),
                )
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                    ArrayList(languages.map { it.bcp47 }),
                )
            }
        }
        try {
            recognizer.startListening(intent)
        } catch (error: Exception) {
            DuiyiLog.w("startListening failed", error)
        }
    }

    companion object {
        fun bestText(bundle: Bundle?): String {
            if (bundle == null) return ""
            val keys = listOf(
                SpeechRecognizer.RESULTS_RECOGNITION,
                "android.speech.extra.UNSTABLE_TEXT",
                "android.speech.extra.RESULTS_PENDING",
                "results_recognition",
            )
            for (key in keys) {
                val fromList = bundle.getStringArrayList(key)?.firstOrNull().orEmpty()
                if (fromList.isNotBlank()) return fromList
                val fromChars = bundle.getCharSequenceArrayList(key)?.firstOrNull()?.toString().orEmpty()
                if (fromChars.isNotBlank()) return fromChars
                val fromString = bundle.getString(key).orEmpty()
                if (fromString.isNotBlank()) return fromString
            }
            return ""
        }

        fun languageFrom(bundle: Bundle?, detected: AppLanguage?, fallback: AppLanguage): AppLanguage {
            val tagged = bundle?.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                ?.let { AppLanguage.fromSpeechLocale(it) }
            return tagged ?: detected ?: fallback
        }

        fun errorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
            else -> "UNKNOWN"
        }
    }
}
