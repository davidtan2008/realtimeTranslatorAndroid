package com.duiyi.android.features.models

import androidx.lifecycle.ViewModel
import com.duiyi.android.app.PreferencesStore
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.ManagedAsset
import com.duiyi.android.domain.ManagedAssetId
import com.duiyi.android.domain.ManagedAssetStatus
import com.duiyi.android.services.assets.DeviceAssetCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val catalog: DeviceAssetCatalog,
    private val preferences: PreferencesStore,
) : ViewModel() {
    val snapshot = catalog.snapshot
    val language: AppLanguage get() = preferences.userLanguage

    init {
        catalog.refresh()
    }

    fun download(id: ManagedAssetId) = catalog.download(id)

    fun downloadMissing() = catalog.downloadMissing()

    fun delete(id: ManagedAssetId) = catalog.delete(id)

    fun statusLabel(asset: ManagedAsset, language: AppLanguage): String = when (asset.status) {
        ManagedAssetStatus.Ready -> Copy.assetReady(language)
        ManagedAssetStatus.Partial -> Copy.assetPartial(language)
        ManagedAssetStatus.Downloading -> Copy.assetDownloading(language)
        ManagedAssetStatus.Failed -> Copy.assetFailed(language)
        ManagedAssetStatus.Missing -> Copy.assetMissing(language)
    }
}
