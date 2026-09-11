package com.duiyi.android.services.speech

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.DeviceCapabilityProbing
import com.duiyi.android.domain.LanguageCapability
import com.duiyi.android.domain.LanguageCataloging
import com.duiyi.android.domain.SpeechProviderKind
import com.duiyi.android.domain.Translating
import com.duiyi.android.domain.TranslationPairStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceLanguageCatalog @Inject constructor(
    private val probe: DeviceCapabilityProbing,
    private val translator: Translating,
) : LanguageCataloging {

    @Volatile
    private var cache: Map<AppLanguage, LanguageCapability> = AppLanguage.entries.associateWith { fallback(it) }

    override val all: List<AppLanguage> = AppLanguage.entries

    override fun capability(language: AppLanguage): LanguageCapability =
        cache[language] ?: fallback(language)

    override suspend fun speechSupported(language: AppLanguage): Boolean = capability(language).speech

    override suspend fun translationPair(from: AppLanguage, to: AppLanguage): TranslationPairStatus =
        translator.pairStatus(from, to)

    override suspend fun refresh() {
        val capabilities = probe.probe()
        val installed = capabilities.installedSpeechLocales.map { it.lowercase() }
        val supported = capabilities.supportedSpeechLocales.map { it.lowercase() }
        cache = AppLanguage.entries.associateWith { language ->
            val tag = language.speechLocale.lowercase()
            val short = language.translateTag
            val sherpa = capabilities.speechProvider == SpeechProviderKind.SherpaOnDevice
            val speech = if (sherpa) {
                language == AppLanguage.ZhHans ||
                    language == AppLanguage.English ||
                    language == AppLanguage.Japanese ||
                    language == AppLanguage.Korean
            } else {
                capabilities.speechAvailable && (
                    installed.any { it.contains(short) || it.contains(tag) } ||
                        supported.any { it.contains(short) || it.contains(tag) } ||
                        capabilities.speechAvailable && (language == AppLanguage.ZhHans || language == AppLanguage.English)
                    )
            }
            val translate = translator.pairStatus(language, language.defaultPeer) != TranslationPairStatus.Unsupported
            fallback(language).copy(speech = speech, translate = translate, speak = speech)
        }
    }

    private fun fallback(language: AppLanguage) = LanguageCapability(
        speechLocale = language.speechLocale,
        ttsVoice = language.ttsVoice,
        speech = language == AppLanguage.ZhHans || language == AppLanguage.English,
        translate = true,
        speak = language == AppLanguage.ZhHans || language == AppLanguage.English,
    )
}
