package com.duiyi.android.app

import android.content.Context
import android.content.SharedPreferences
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.CaptionFontSize
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("duiyi.prefs", Context.MODE_PRIVATE)

    var userLanguage: AppLanguage
        get() = language("userLanguage") ?: AppLanguage.inferredFromSystem(
            java.util.Locale.getDefault().toLanguageTag(),
        )
        set(value) = prefs.edit().putString("userLanguage", value.name).apply()

    var peerLanguage: AppLanguage
        get() = language("peerLanguage") ?: userLanguage.defaultPeer
        set(value) = prefs.edit().putString("peerLanguage", value.name).apply()

    var sourceLanguage: AppLanguage
        get() = language("sourceLanguage") ?: userLanguage.defaultPeer
        set(value) = prefs.edit().putString("sourceLanguage", value.name).apply()

    var targetLanguage: AppLanguage
        get() = language("targetLanguage") ?: userLanguage
        set(value) = prefs.edit().putString("targetLanguage", value.name).apply()

    var meetingLanguage: AppLanguage
        get() = language("meetingLanguage") ?: userLanguage
        set(value) = prefs.edit().putString("meetingLanguage", value.name).apply()

    var fontSize: CaptionFontSize
        get() = CaptionFontSize.entries.find { it.name == prefs.getString("fontSize", null) }
            ?: CaptionFontSize.Standard
        set(value) = prefs.edit().putString("fontSize", value.name).apply()

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("onboarding", false)
        set(value) = prefs.edit().putBoolean("onboarding", value).apply()

    var autoStartListening: Boolean
        get() = prefs.getBoolean("autoStart", true)
        set(value) = prefs.edit().putBoolean("autoStart", value).apply()

    var voiceOutEnabled: Boolean
        get() = prefs.getBoolean("voiceOut", false)
        set(value) = prefs.edit().putBoolean("voiceOut", value).apply()

    var showsSourceLine: Boolean
        get() = prefs.getBoolean("showsSource", true)
        set(value) = prefs.edit().putBoolean("showsSource", value).apply()

    private fun language(key: String): AppLanguage? =
        prefs.getString(key, null)?.let { raw -> AppLanguage.entries.find { it.name == raw } }
}
