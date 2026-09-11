package com.duiyi.android.services.speech

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import com.duiyi.android.domain.DeviceCapabilities
import com.duiyi.android.domain.DeviceCapabilityProbing
import com.duiyi.android.domain.SpeechProviderKind
import com.duiyi.android.domain.TranslationProviderKind
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceCapabilityProbing {

    @Volatile
    private var cached: Pair<Long, DeviceCapabilities>? = null

    override suspend fun probe(): DeviceCapabilities {
        cached?.let { (at, value) ->
            if (System.currentTimeMillis() - at < 60_000) return value
        }
        val value = withContext(Dispatchers.Main) { probeNow() }
        cached = System.currentTimeMillis() to value
        return value
    }

    private fun probeNow(): DeviceCapabilities {
        val play = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val system = SpeechRecognizer.isRecognitionAvailable(context)
        val googleApp = SpeechRecognizerFactory.hasGoogleApp(context)
        val honor = SpeechEnginePolicy.isHonorFamily(Build.MANUFACTURER, Build.BRAND)
        val speechProvider = when {
            honor && SpeechEnginePolicy.useSherpaOnDevice(honor) -> SpeechProviderKind.SherpaOnDevice
            !honor && onDevice -> SpeechProviderKind.GoogleOnDevice
            googleApp || system || onDevice -> SpeechProviderKind.GoogleSystemPreferOffline
            else -> SpeechProviderKind.Unavailable
        }
        return DeviceCapabilities(
            playServicesAvailable = play,
            speechAvailable = speechProvider != SpeechProviderKind.Unavailable,
            speechProvider = speechProvider,
            translationProvider = TranslationProviderKind.OnDeviceGemma,
            onDeviceSpeechAvailable = onDevice,
            installedSpeechLocales = emptySet(),
            supportedSpeechLocales = emptySet(),
        )
    }
}
