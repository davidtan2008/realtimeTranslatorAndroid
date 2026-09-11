package com.duiyi.android.designsystem

import androidx.compose.ui.graphics.Color
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.FeatureID

object Tokens {
    val canvas = Color(0xFF12141A)
    val userPane = Color(0xFF161920)
    val peerPane = Color(0xFF1A1714)
    val textPrimary = Color(0xFFF4EFE6)
    val textSecondary = Color(0x94F4EFE6)
    val listen = Color(0xFFE8B86D)
    val danger = Color(0xFFE07070)
    val controlIdle = Color(0xFF2A2E38)
    val sheet = Color(0xFF23262F)
    val sheetChip = Color(0xFF2F3340)
    val cardFaceToFace = Color(0xFF1A1714)
    val cardSimultaneous = Color(0xFF161920)
    val cardMeeting = Color(0xFF1C1824)
    val languageEN = Color(0xFF7EC8C3)
    val featureMeeting = Color(0xFFC49BE8)

    fun featureAccent(id: FeatureID) = when (id) {
        FeatureID.FaceToFace -> listen
        FeatureID.Simultaneous -> languageEN
        FeatureID.Meeting -> featureMeeting
    }

    fun featureCard(id: FeatureID) = when (id) {
        FeatureID.FaceToFace -> cardFaceToFace
        FeatureID.Simultaneous -> cardSimultaneous
        FeatureID.Meeting -> cardMeeting
    }

    fun languageBadge(language: AppLanguage) = when (language) {
        AppLanguage.ZhHans -> listen
        AppLanguage.English -> languageEN
        AppLanguage.Japanese -> Color(0xFFE07A7A)
        AppLanguage.Korean -> Color(0xFF7EA0E8)
        AppLanguage.Thai -> Color(0xFFC49BE8)
        AppLanguage.Vietnamese -> Color(0xFF7ECF9A)
    }
}
