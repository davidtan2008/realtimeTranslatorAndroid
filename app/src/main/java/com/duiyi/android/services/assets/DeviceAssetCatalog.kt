package com.duiyi.android.services.assets

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.DeviceAssetSnapshot
import com.duiyi.android.domain.ManagedAsset
import com.duiyi.android.domain.ManagedAssetFile
import com.duiyi.android.domain.ManagedAssetId
import com.duiyi.android.domain.ManagedAssetRules
import com.duiyi.android.domain.ManagedAssetStatus
import com.duiyi.android.domain.Translating
import com.duiyi.android.services.DuiyiLog
import com.duiyi.android.services.speech.SherpaDownloadProgress
import com.duiyi.android.services.speech.SherpaModelStore
import com.duiyi.android.services.translation.GemmaDownloadProgress
import com.duiyi.android.services.translation.GemmaModelStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Singleton
class DeviceAssetCatalog @Inject constructor(
    private val sherpa: SherpaModelStore,
    private val gemma: GemmaModelStore,
    private val translator: Translating,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var translationJob: Job? = null
    private val translationState = MutableStateFlow(PackState())
    private val _snapshot = MutableStateFlow(DeviceAssetSnapshot())
    val snapshot: StateFlow<DeviceAssetSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            sherpa.progress.collect { progress ->
                _snapshot.update { it.copy(speech = speechAsset(progress)) }
            }
        }
        scope.launch {
            gemma.progress.collect { progress ->
                _snapshot.update {
                    it.copy(
                        loaded = true,
                        translationAvailable = true,
                        translations = listOf(translationAsset(progress, translationState.value)),
                    )
                }
            }
        }
        scope.launch {
            translationState.collect { state ->
                _snapshot.update {
                    it.copy(
                        loaded = true,
                        translationAvailable = true,
                        translations = listOf(translationAsset(gemma.progress.value, state)),
                    )
                }
            }
        }
    }

    fun refresh() {
        scope.launch {
            _snapshot.update {
                it.copy(
                    loaded = true,
                    speech = speechAsset(sherpa.progress.value),
                    translationAvailable = true,
                    translations = listOf(translationAsset(gemma.progress.value, translationState.value)),
                )
            }
        }
    }

    fun download(id: ManagedAssetId) {
        when (id) {
            ManagedAssetId.SpeechPack -> downloadSpeech()
            ManagedAssetId.TranslationModel -> downloadTranslation()
        }
    }

    fun delete(id: ManagedAssetId) {
        scope.launch {
            when (id) {
                ManagedAssetId.SpeechPack -> {
                    if (sherpa.progress.value.running) return@launch
                    runCatching { sherpa.deleteAll() }
                        .onFailure { DuiyiLog.w("delete speech pack failed", it) }
                }
                ManagedAssetId.TranslationModel -> {
                    if (translationJob?.isActive == true) return@launch
                    runCatching { translator.deleteLanguage(AppLanguage.English) }
                        .onFailure { DuiyiLog.w("delete translation model failed", it) }
                    translationState.value = PackState()
                }
            }
        }
    }

    fun downloadMissing() {
        val current = _snapshot.value
        current.speech?.let { if (it.canDownload) downloadSpeech() }
        current.translations.firstOrNull()?.let { if (it.canDownload) downloadTranslation() }
    }

    private fun downloadSpeech() {
        if (sherpa.progress.value.running) return
        scope.launch {
            runCatching { sherpa.ensureReady() }
                .onFailure { DuiyiLog.w("speech pack download failed", it) }
        }
    }

    private fun downloadTranslation() {
        if (translationJob?.isActive == true) return
        translationJob = scope.launch {
            translationState.value = PackState(running = true)
            runCatching { translator.downloadLanguage(AppLanguage.English) }
                .onFailure {
                    DuiyiLog.w("translation model download failed", it)
                    translationState.value = PackState(failed = true)
                }
                .onSuccess {
                    translationState.value = PackState()
                }
        }
    }

    private fun speechAsset(progress: SherpaDownloadProgress): ManagedAsset? {
        val snap = sherpa.snapshot()
        if (!snap.visible) return null
        val status = ManagedAssetRules.status(
            ready = snap.ready,
            running = progress.running,
            failed = progress.failed,
            bytes = snap.bytes,
        )
        val fraction = when (status) {
            ManagedAssetStatus.Downloading -> progress.fraction
            ManagedAssetStatus.Ready -> 1f
            ManagedAssetStatus.Partial -> {
                (snap.bytes.toFloat() / snap.expectedBytes.coerceAtLeast(1).toFloat()).coerceIn(0f, 0.99f)
            }
            else -> 0f
        }
        return ManagedAsset(
            id = ManagedAssetId.SpeechPack,
            status = status,
            bytes = snap.bytes,
            expectedBytes = snap.expectedBytes,
            progress = fraction,
            files = snap.files.map { file ->
                ManagedAssetFile(file.fileName, file.bytes, file.expectedBytes, file.ready)
            },
        )
    }

    private fun translationAsset(progress: GemmaDownloadProgress, state: PackState): ManagedAsset {
        val snap = gemma.snapshot()
        val status = ManagedAssetRules.status(
            ready = snap.ready,
            running = progress.running || state.running,
            failed = progress.failed || state.failed,
            bytes = snap.bytes,
        )
        val fraction = when (status) {
            ManagedAssetStatus.Downloading -> progress.fraction
            ManagedAssetStatus.Ready -> 1f
            ManagedAssetStatus.Partial -> {
                (snap.bytes.toFloat() / snap.expectedBytes.coerceAtLeast(1).toFloat()).coerceIn(0f, 0.99f)
            }
            else -> 0f
        }
        return ManagedAsset(
            id = ManagedAssetId.TranslationModel,
            status = status,
            bytes = snap.bytes,
            expectedBytes = snap.expectedBytes,
            progress = fraction,
            files = listOf(
                ManagedAssetFile(
                    name = "translategemma-4b-it.Q4_K_M.gguf",
                    bytes = snap.bytes,
                    expectedBytes = snap.expectedBytes,
                    ready = snap.ready,
                ),
            ),
        )
    }

    private data class PackState(
        val running: Boolean = false,
        val failed: Boolean = false,
    )
}
