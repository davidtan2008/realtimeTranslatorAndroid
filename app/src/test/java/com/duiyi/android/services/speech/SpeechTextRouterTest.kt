package com.duiyi.android.services.speech

import com.duiyi.android.domain.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextRouterTest {
    @Test
    fun bilingualPrefersScriptWhenOemLanguageMismatches() {
        val language = SpeechTextRouter.languageFor(
            text = "我们六点见",
            bilingual = true,
            detected = AppLanguage.English,
            primary = AppLanguage.ZhHans,
            claimed = AppLanguage.English,
        )
        assertEquals(AppLanguage.ZhHans, language)
    }

    @Test
    fun bilingualKeepsDetectedWhenScriptAgrees() {
        val language = SpeechTextRouter.languageFor(
            text = "see you at six",
            bilingual = true,
            detected = AppLanguage.English,
            primary = AppLanguage.ZhHans,
            claimed = AppLanguage.English,
        )
        assertEquals(AppLanguage.English, language)
    }

    @Test
    fun bilingualSwitchesWhenScriptClearlyChanges() {
        val language = SpeechTextRouter.languageFor(
            text = "today is sunny",
            bilingual = true,
            detected = AppLanguage.ZhHans,
            primary = AppLanguage.ZhHans,
            claimed = AppLanguage.ZhHans,
        )
        assertEquals(AppLanguage.English, language)
    }

    @Test
    fun monolingualStaysOnPrimary() {
        val language = SpeechTextRouter.languageFor(
            text = "我们六点见",
            bilingual = false,
            detected = AppLanguage.English,
            primary = AppLanguage.English,
            claimed = AppLanguage.English,
        )
        assertEquals(AppLanguage.English, language)
    }
}
