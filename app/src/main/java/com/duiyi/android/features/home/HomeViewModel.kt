package com.duiyi.android.features.home

import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duiyi.android.app.PreferencesStore
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.CaptionFontSize
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.DeviceCapabilities
import com.duiyi.android.domain.DeviceCapabilityProbing
import com.duiyi.android.domain.LanguageCataloging
import com.duiyi.android.domain.SpeechProviderKind
import com.duiyi.android.domain.TaskSession
import com.duiyi.android.domain.TaskStore
import com.duiyi.android.domain.TranslationProviderKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val language: AppLanguage = AppLanguage.ZhHans,
    val unfinished: List<TaskSession> = emptyList(),
    val settingsOpen: Boolean = false,
    val fontSize: CaptionFontSize = CaptionFontSize.Standard,
    val capabilities: DeviceCapabilities? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val store: TaskStore,
    private val preferences: PreferencesStore,
    private val probe: DeviceCapabilityProbing,
    val catalog: LanguageCataloging,
) : ViewModel() {
    private val _state = MutableStateFlow(
        HomeUiState(language = preferences.userLanguage, fontSize = preferences.fontSize),
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            catalog.refresh()
            val capabilities = probe.probe()
            _state.update {
                it.copy(
                    language = preferences.userLanguage,
                    fontSize = preferences.fontSize,
                    unfinished = store.unfinishedTasks(),
                    capabilities = capabilities,
                )
            }
        }
    }

    fun openSettings(open: Boolean) = _state.update { it.copy(settingsOpen = open) }

    fun setUserLanguage(language: AppLanguage) {
        preferences.userLanguage = language
        if (preferences.peerLanguage == language) {
            preferences.peerLanguage = language.defaultPeer
        }
        _state.update { it.copy(language = language) }
    }

    fun setFontSize(size: CaptionFontSize) {
        preferences.fontSize = size
        _state.update { it.copy(fontSize = size) }
    }

    fun speechEngineText(): String {
        val language = _state.value.language
        return when (_state.value.capabilities?.speechProvider) {
            SpeechProviderKind.GoogleOnDevice, SpeechProviderKind.SherpaOnDevice ->
                Copy.capabilitySpeechOnDevice(language)
            SpeechProviderKind.GoogleSystemPreferOffline -> Copy.capabilitySpeechSystem(language)
            SpeechProviderKind.Unavailable, null -> Copy.capabilitySpeechNone(language)
        }
    }

    fun gmsText(): String = translationEngineText()

    fun translationEngineText(): String {
        val language = _state.value.language
        return when (_state.value.capabilities?.translationProvider) {
            TranslationProviderKind.OnDeviceGemma -> Copy.capabilityTranslationOnDevice(language)
            else -> Copy.capabilityTranslationMissing(language)
        }
    }

    fun systemSettingsIntent(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
}
