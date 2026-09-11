package com.duiyi.android.domain

sealed interface ManagedAssetId {
    data object SpeechPack : ManagedAssetId
    data object TranslationModel : ManagedAssetId
}

enum class ManagedAssetStatus {
    Missing,
    Partial,
    Ready,
    Downloading,
    Failed,
}

data class ManagedAssetFile(
    val name: String,
    val bytes: Long,
    val expectedBytes: Long,
    val ready: Boolean,
)

data class ManagedAsset(
    val id: ManagedAssetId,
    val status: ManagedAssetStatus,
    val bytes: Long,
    val expectedBytes: Long,
    val progress: Float,
    val indeterminate: Boolean = false,
    val files: List<ManagedAssetFile> = emptyList(),
) {
    val canDownload: Boolean
        get() = status == ManagedAssetStatus.Missing ||
            status == ManagedAssetStatus.Partial ||
            status == ManagedAssetStatus.Failed

    val canDelete: Boolean
        get() = status == ManagedAssetStatus.Ready || status == ManagedAssetStatus.Partial
}

data class DeviceAssetSnapshot(
    val loaded: Boolean = false,
    val speech: ManagedAsset? = null,
    val translations: List<ManagedAsset> = emptyList(),
    val translationAvailable: Boolean = true,
) {
    val hasMissing: Boolean
        get() {
            val speechMissing = speech?.let { it.status != ManagedAssetStatus.Ready && it.status != ManagedAssetStatus.Downloading } == true
            val packsMissing = translationAvailable && translations.any {
                it.status == ManagedAssetStatus.Missing || it.status == ManagedAssetStatus.Failed
            }
            return speechMissing || packsMissing
        }

    val downloading: Boolean
        get() = speech?.status == ManagedAssetStatus.Downloading ||
            translations.any { it.status == ManagedAssetStatus.Downloading }
}

object ManagedAssetRules {
    fun status(ready: Boolean, running: Boolean, failed: Boolean, bytes: Long): ManagedAssetStatus = when {
        running -> ManagedAssetStatus.Downloading
        failed && !ready -> ManagedAssetStatus.Failed
        ready -> ManagedAssetStatus.Ready
        bytes > 0L -> ManagedAssetStatus.Partial
        else -> ManagedAssetStatus.Missing
    }
}
