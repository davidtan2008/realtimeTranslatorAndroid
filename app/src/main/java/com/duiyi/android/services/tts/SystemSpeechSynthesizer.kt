package com.duiyi.android.services.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.SpeechSynthesizing
import com.duiyi.android.services.DuiyiLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class SystemSpeechSynthesizer @Inject constructor(
    @ApplicationContext context: Context,
) : SpeechSynthesizing {

    private val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CancellableContinuation<Unit>>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setAcceptsDelayedFocusGain(false)
        .build()
    private val tts = TextToSpeech(context) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    @Volatile
    private var configured = false

    override suspend fun enqueue(text: String, language: AppLanguage) {
        if (text.isBlank()) return
        val ok = ready.await()
        if (!ok) {
            DuiyiLog.w("tts engine failed to start")
            return
        }
        ensureConfigured()
        val locale = Locale.forLanguageTag(language.bcp47)
        val available = tts.setLanguage(locale)
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            DuiyiLog.w("tts voice missing for ${language.speechLocale}")
            return
        }
        tts.setSpeechRate(1.08f)
        val id = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        audioManager.requestAudioFocus(focusRequest)
        try {
            val finished = withTimeoutOrNull(12_000L) {
                suspendCancellableCoroutine { cont ->
                    pending[id] = cont
                    cont.invokeOnCancellation {
                        pending.remove(id)
                        tts.stop()
                    }
                    val started = tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
                    if (started != TextToSpeech.SUCCESS) {
                        DuiyiLog.w("tts speak failed language=${language.speechLocale}")
                        pending.remove(id)
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            if (finished == null) {
                DuiyiLog.w("tts timed out language=${language.speechLocale}")
                tts.stop()
            }
        } finally {
            pending.remove(id)
            if (pending.isEmpty()) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }
    }

    override suspend fun stopAndClear() {
        pending.values.toList().forEach { cont ->
            if (cont.isActive) cont.resume(Unit)
        }
        pending.clear()
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (!ready.isCompleted) return
        tts.stop()
    }

    override suspend fun hasVoice(language: AppLanguage): Boolean {
        val ok = ready.await()
        if (!ok) return false
        ensureConfigured()
        val result = tts.isLanguageAvailable(Locale.forLanguageTag(language.bcp47))
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    private fun ensureConfigured() {
        if (configured) return
        tts.setAudioAttributes(audioAttributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                complete(utteranceId)
            }
            override fun onError(utteranceId: String?) {
                complete(utteranceId)
            }
        })
        configured = true
    }

    private fun complete(utteranceId: String?) {
        val cont = pending.remove(utteranceId) ?: return
        if (cont.isActive) cont.resume(Unit)
    }
}
