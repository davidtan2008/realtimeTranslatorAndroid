package com.duiyi.android.domain.routing

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.SpeakerSide
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageRouterTest {
    @Test
    fun userSpeechLandsOnPeerPane() {
        assertEquals(SpeakerSide.Peer, LanguageRouter.targetSide(AppLanguage.ZhHans, AppLanguage.ZhHans))
        assertEquals(AppLanguage.English, LanguageRouter.targetLanguage(AppLanguage.ZhHans, AppLanguage.ZhHans, AppLanguage.English))
        assertEquals(SpeakerSide.User, LanguageRouter.speakerSide(AppLanguage.ZhHans, AppLanguage.ZhHans))
    }

    @Test
    fun peerSpeechLandsOnUserPane() {
        assertEquals(SpeakerSide.User, LanguageRouter.targetSide(AppLanguage.English, AppLanguage.ZhHans))
        assertEquals(AppLanguage.ZhHans, LanguageRouter.targetLanguage(AppLanguage.English, AppLanguage.ZhHans, AppLanguage.English))
    }
}
