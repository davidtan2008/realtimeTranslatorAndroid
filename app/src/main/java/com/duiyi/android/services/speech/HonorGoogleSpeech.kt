package com.duiyi.android.services.speech

import android.app.PendingIntent
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import com.duiyi.android.app.MainActivity

object HonorGoogleSpeech {
    const val ACTION_SPEECH_RESULT = "com.duiyi.android.action.SPEECH_RESULT"

    private val googlePackage = SpeechRecognizerFactory.GOOGLE.packageName
    private val handsFree = ComponentName(
        googlePackage,
        "com.google.android.voicesearch.handsfree.HandsFreeActivity",
    )

    fun uiIntents(context: Context): List<Intent> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Intent>()
        fun add(label: String, intent: Intent) {
            if (!isGoogleDictationTarget(context, intent)) return
            if (!seen.add(label)) return
            intent.putExtra("duiyi.ui.label", label)
            out += intent
        }

        add("handsfree-plain", handsFreeIntent())
        add(
            "handsfree-antisearch",
            handsFreeIntent().apply {
                putExtra(RecognizerIntent.EXTRA_WEB_SEARCH_ONLY, false)
                putExtra("android.speech.extra.WEB_SEARCH_ONLY", false)
                putExtra("android.speech.extra.DICTATION_MODE", true)
            },
        )
        add("handsfree-broadcast", handsFreeIntent().also { attachBroadcastPipe(context, it) })
        add("handsfree-activity", handsFreeIntent().also { attachActivityPipe(context, it) })
        return out
    }

    fun isGoogleDictationTarget(context: Context, intent: Intent): Boolean {
        val resolved = intent.component ?: intent.resolveActivity(context.packageManager) ?: return false
        val pkg = resolved.packageName
        if (pkg.contains("settings", ignoreCase = true)) return false
        if (SpeechEnginePolicy.isVendorMuteSpeechPackage(pkg)) return false
        return SpeechEnginePolicy.isGoogleSpeechPackage(pkg)
    }

    fun attachBroadcastPipe(context: Context, intent: Intent) {
        val broadcast = Intent(context, SpeechResultReceiver::class.java).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(
            context,
            18,
            broadcast,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        intent.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, pending)
        intent.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE, Bundle())
    }

    fun attachActivityPipe(context: Context, intent: Intent) {
        val result = Intent(context, MainActivity::class.java)
            .setAction(ACTION_SPEECH_RESULT)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        val pending = PendingIntent.getActivity(
            context,
            17,
            result,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        intent.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, pending)
        intent.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE, Bundle())
    }

    fun bestText(data: Intent?): String {
        if (data == null) return ""
        val extras = data.extras ?: return ""
        val listKeys = listOf(
            RecognizerIntent.EXTRA_RESULTS,
            "android.speech.extra.RESULTS",
            "android.speech.extra.UNSTABLE_TEXT",
        )
        for (key in listKeys) {
            val fromList = extras.getStringArrayList(key)?.firstOrNull().orEmpty()
            if (fromList.isNotBlank()) return fromList.trim()
        }
        val stringKeys = listOf(
            RecognizerIntent.EXTRA_RESULTS,
            SearchManager.QUERY,
            Intent.EXTRA_TEXT,
            "query",
            "android.speech.extra.UNSTABLE_TEXT",
        )
        for (key in stringKeys) {
            val value = extras.getString(key).orEmpty().trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    fun extraKeys(data: Intent?): String = data?.extras?.keySet()?.joinToString(",").orEmpty()

    fun describe(intent: Intent): String =
        intent.getStringExtra("duiyi.ui.label")
            ?: intent.component?.flattenToShortString()
            ?: intent.action.orEmpty()

    private fun handsFreeIntent(): Intent =
        Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE)
            .setComponent(handsFree)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
}
