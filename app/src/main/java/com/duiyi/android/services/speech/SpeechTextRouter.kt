package com.duiyi.android.services.speech

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.SpokenText
import com.duiyi.android.domain.routing.UtteranceAssembler

object SpeechTextRouter {
    fun languageFor(
        text: String,
        bilingual: Boolean,
        detected: AppLanguage?,
        primary: AppLanguage,
        claimed: AppLanguage,
    ): AppLanguage {
        val script = SpokenText.inferFromScript(text, claimed)
        if (!bilingual) return primary
        if (SpokenText.scriptIsDecisive(text)) return script
        if (detected != null && UtteranceAssembler.matchesClaimedLanguage(text, detected)) {
            return detected
        }
        return script
    }
}
