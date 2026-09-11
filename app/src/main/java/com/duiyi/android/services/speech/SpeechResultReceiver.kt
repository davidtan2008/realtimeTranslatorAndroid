package com.duiyi.android.services.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.duiyi.android.services.DuiyiLog
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint

class SpeechResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DuiyiLog.i("speech honor-intent receiver keys=${intent.extras?.keySet()}")
        SpeechProbe.i(context, "receiver keys=${intent.extras?.keySet()} textLen=${HonorGoogleSpeech.bestText(intent).length}")
        val text = HonorGoogleSpeech.bestText(intent)
        if (text.isBlank()) return
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SpeechBridgeEntry::class.java,
        ).speechIntentBridge().onSpeechText(text, context)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SpeechBridgeEntry {
    fun speechIntentBridge(): SpeechIntentBridge
}
