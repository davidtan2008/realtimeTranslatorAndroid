package com.duiyi.android.domain

enum class FeatureID {
    FaceToFace,
    Simultaneous,
    Meeting;

    val route: String
        get() = when (this) {
            FaceToFace -> "conversation"
            Simultaneous -> "interpretation"
            Meeting -> "meeting"
        }
}

data class FeatureDescriptor(
    val id: FeatureID,
    val title: (AppLanguage) -> String,
    val subtitle: (AppLanguage) -> String,
)
