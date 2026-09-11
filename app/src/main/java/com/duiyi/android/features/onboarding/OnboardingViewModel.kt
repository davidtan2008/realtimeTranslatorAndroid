package com.duiyi.android.features.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duiyi.android.app.PreferencesStore
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.DeviceCapabilities
import com.duiyi.android.domain.DeviceCapabilityProbing
import com.duiyi.android.domain.DuiyiError
import com.duiyi.android.domain.LanguageCataloging
import com.duiyi.android.domain.PermissionGranting
import com.duiyi.android.domain.PermissionStatus
import com.duiyi.android.domain.SpeechProviderKind
import com.duiyi.android.domain.Translating
import com.duiyi.android.domain.TranslationProviderKind
import com.duiyi.android.services.speech.SherpaModelStore
import com.duiyi.android.services.speech.SpeechEngineException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { Welcome, Language, Permission, Assets }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val language: AppLanguage = AppLanguage.ZhHans,
    val peerLanguage: AppLanguage = AppLanguage.English,
    val working: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val capabilities: DeviceCapabilities? = null,
    val prepared: Boolean = false,
    val finished: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: PreferencesStore,
    private val permissions: PermissionGranting,
    private val probe: DeviceCapabilityProbing,
    val catalog: LanguageCataloging,
    private val translator: Translating,
    private val sherpaModels: SherpaModelStore,
) : ViewModel() {
    private val _state = MutableStateFlow(
        OnboardingUiState(
            language = preferences.userLanguage,
            peerLanguage = preferences.peerLanguage,
        ),
    )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun title(): String {
        val language = _state.value.language
        return when (_state.value.step) {
            OnboardingStep.Welcome -> Copy.appTitle(language)
            OnboardingStep.Language -> Copy.languageQuestion(language)
            OnboardingStep.Permission -> Copy.permissionTitle(language)
            OnboardingStep.Assets -> Copy.assetsTitle(language)
        }
    }

    fun body(): String {
        val language = _state.value.language
        return when (_state.value.step) {
            OnboardingStep.Welcome -> Copy.welcomeBody(language)
            OnboardingStep.Language -> Copy.languageBody(language)
            OnboardingStep.Permission -> Copy.permissionBody(language)
            OnboardingStep.Assets -> if (sherpaModels.isRequired()) {
                Copy.assetsBodyHonor(language)
            } else {
                Copy.assetsBody(language)
            }
        }
    }

    fun primaryLabel(): String {
        val s = _state.value
        val language = s.language
        return when (s.step) {
            OnboardingStep.Welcome -> Copy.getStarted(language)
            OnboardingStep.Language -> Copy.continueButton(language)
            OnboardingStep.Permission -> Copy.allow(language)
            OnboardingStep.Assets -> when {
                s.prepared -> Copy.enterHome(language)
                s.error != null -> Copy.retry(language)
                s.working -> Copy.preparing(language)
                else -> Copy.downloadModels(language)
            }
        }
    }

    fun capabilityHint(): String? {
        val s = _state.value
        val language = s.language
        val caps = s.capabilities ?: return null
        val parts = buildList {
            add(
                when (caps.speechProvider) {
                    SpeechProviderKind.GoogleOnDevice, SpeechProviderKind.SherpaOnDevice ->
                        Copy.capabilitySpeechOnDevice(language)
                    SpeechProviderKind.GoogleSystemPreferOffline -> Copy.capabilitySpeechSystem(language)
                    SpeechProviderKind.Unavailable -> Copy.capabilitySpeechNone(language)
                },
            )
            add(
                when (caps.translationProvider) {
                    TranslationProviderKind.OnDeviceGemma -> Copy.capabilityTranslationOnDevice(language)
                    TranslationProviderKind.Unavailable -> Copy.capabilityTranslationMissing(language)
                },
            )
            if (!caps.playServicesAvailable && caps.speechProvider != SpeechProviderKind.SherpaOnDevice) {
                add(Copy.noGmsHint(language))
            }
        }
        return parts.joinToString("\n")
    }

    fun advanceWelcome() = _state.update { it.copy(step = OnboardingStep.Language, error = null) }

    fun selectLanguage(language: AppLanguage) {
        var peer = _state.value.peerLanguage
        if (language == peer) peer = language.defaultPeer
        preferences.userLanguage = language
        preferences.peerLanguage = peer
        _state.update { it.copy(language = language, peerLanguage = peer) }
    }

    fun selectPeer(language: AppLanguage) {
        var user = _state.value.language
        if (language == user) user = _state.value.peerLanguage
        preferences.userLanguage = user
        preferences.peerLanguage = language
        _state.update { it.copy(language = user, peerLanguage = language) }
    }

    fun advanceLanguage() {
        val s = _state.value
        if (s.peerLanguage == s.language) {
            val peer = s.language.defaultPeer
            preferences.peerLanguage = peer
            _state.update { it.copy(peerLanguage = peer, step = OnboardingStep.Permission) }
        } else {
            _state.update { it.copy(step = OnboardingStep.Permission) }
        }
    }

    fun requestPermission() {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            val status = permissions.requestAccess()
            if (status == PermissionStatus.Granted) {
                _state.update { it.copy(working = false, step = OnboardingStep.Assets) }
                prepareAssets()
            } else {
                _state.update {
                    it.copy(working = false, error = DuiyiError.MicrophoneDenied.userMessage(it.language))
                }
            }
        }
    }

    fun prepareAssets() {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null, progress = 0f, prepared = false) }
            catalog.refresh()
            val capabilities = probe.probe()
            _state.update { it.copy(capabilities = capabilities) }
            runCatching {
                val speechWeight = if (sherpaModels.isRequired()) 0.12f else 0f
                if (sherpaModels.isRequired()) {
                    sherpaModels.ensureReady { value ->
                        _state.update { it.copy(progress = value * speechWeight) }
                    }
                }
                translator.download { value ->
                    _state.update { it.copy(progress = speechWeight + value * (1f - speechWeight)) }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        working = false,
                        error = when ((error as? SpeechEngineException)?.kind) {
                            DuiyiError.SpeechModelsMissing -> DuiyiError.SpeechModelsMissing.userMessage(it.language)
                            DuiyiError.ModelsMissing -> DuiyiError.ModelsMissing.userMessage(it.language)
                            else -> DuiyiError.ModelsMissing.userMessage(it.language)
                        },
                        prepared = true,
                    )
                }
            }.onSuccess {
                _state.update { it.copy(working = false, progress = 1f, prepared = true) }
            }
        }
    }

    fun finish() {
        preferences.userLanguage = _state.value.language
        preferences.peerLanguage = _state.value.peerLanguage
        preferences.hasCompletedOnboarding = true
        _state.update { it.copy(finished = true) }
    }

    fun primaryAction() {
        when (_state.value.step) {
            OnboardingStep.Welcome -> advanceWelcome()
            OnboardingStep.Language -> advanceLanguage()
            OnboardingStep.Permission -> requestPermission()
            OnboardingStep.Assets -> {
                if (_state.value.prepared) finish()
                else if (!_state.value.working) prepareAssets()
            }
        }
    }
}
