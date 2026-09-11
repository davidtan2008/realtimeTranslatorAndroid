package com.duiyi.android.domain

enum class AppLanguage {
    ZhHans,
    English,
    Japanese,
    Korean,
    Thai,
    Vietnamese;

    val nativeName: String
        get() = when (this) {
            ZhHans -> "中文"
            English -> "English"
            Japanese -> "日本語"
            Korean -> "한국어"
            Thai -> "ไทย"
            Vietnamese -> "Tiếng Việt"
        }

    val badge: String
        get() = when (this) {
            ZhHans -> "ZH"
            English -> "EN"
            Japanese -> "JA"
            Korean -> "KO"
            Thai -> "TH"
            Vietnamese -> "VI"
        }

    val speechLocale: String
        get() = when (this) {
            ZhHans -> "zh-CN"
            English -> "en-US"
            Japanese -> "ja-JP"
            Korean -> "ko-KR"
            Thai -> "th-TH"
            Vietnamese -> "vi-VN"
        }

    val bcp47: String
        get() = when (this) {
            ZhHans -> "zh-CN"
            English -> "en-US"
            Japanese -> "ja-JP"
            Korean -> "ko-KR"
            Thai -> "th-TH"
            Vietnamese -> "vi-VN"
        }

    val translateTag: String
        get() = when (this) {
            ZhHans -> "zh"
            English -> "en"
            Japanese -> "ja"
            Korean -> "ko"
            Thai -> "th"
            Vietnamese -> "vi"
        }

    val ttsVoice: String get() = speechLocale

    val defaultPeer: AppLanguage
        get() = if (this == English) ZhHans else English

    val usesLatinScript: Boolean
        get() = this == English || this == Vietnamese

    fun pick(zh: String, en: String, ja: String, ko: String, th: String, vi: String): String =
        when (this) {
            ZhHans -> zh
            English -> en
            Japanese -> ja
            Korean -> ko
            Thai -> th
            Vietnamese -> vi
        }

    companion object {
        fun inferredFromSystem(tag: String): AppLanguage {
            val lower = tag.lowercase()
            return when {
                lower.startsWith("zh") -> ZhHans
                lower.startsWith("ja") -> Japanese
                lower.startsWith("ko") -> Korean
                lower.startsWith("th") -> Thai
                lower.startsWith("vi") -> Vietnamese
                else -> English
            }
        }

        fun fromSpeechLocale(tag: String): AppLanguage? {
            val lower = tag.lowercase().replace('_', '-')
            return entries.firstOrNull {
                it.speechLocale.lowercase() == lower ||
                    it.translateTag == lower ||
                    lower.startsWith(it.translateTag)
            } ?: when {
                lower.startsWith("zh") -> ZhHans
                lower.startsWith("en") -> English
                lower.startsWith("ja") -> Japanese
                lower.startsWith("ko") -> Korean
                lower.startsWith("th") -> Thai
                lower.startsWith("vi") -> Vietnamese
                else -> null
            }
        }
    }
}

enum class CaptionFontSize {
    Standard,
    Larger,
    Largest;

    val liveSp: Int
        get() = when (this) {
            Standard -> 22
            Larger -> 28
            Largest -> 34
        }

    val previousSp: Int
        get() = when (this) {
            Standard -> 14
            Larger -> 16
            Largest -> 18
        }
}

enum class SessionState {
    Idle,
    Preparing,
    Ready,
    Listening,
    Paused,
    Failed,
}

enum class PermissionStatus {
    Unknown,
    Granted,
    MicrophoneDenied,
    ;

    fun asDuiyiError(): DuiyiError? = when (this) {
        Granted, Unknown -> null
        MicrophoneDenied -> DuiyiError.MicrophoneDenied
    }
}

enum class SpeechProviderKind {
    GoogleOnDevice,
    GoogleSystemPreferOffline,
    SherpaOnDevice,
    Unavailable,
}

enum class TranslationProviderKind {
    OnDeviceGemma,
    Unavailable,
}

data class DeviceCapabilities(
    val playServicesAvailable: Boolean,
    val speechAvailable: Boolean,
    val speechProvider: SpeechProviderKind,
    val translationProvider: TranslationProviderKind,
    val onDeviceSpeechAvailable: Boolean,
    val installedSpeechLocales: Set<String>,
    val supportedSpeechLocales: Set<String>,
)
