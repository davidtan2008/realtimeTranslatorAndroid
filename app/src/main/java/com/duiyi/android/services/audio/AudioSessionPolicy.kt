package com.duiyi.android.services.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.duiyi.android.domain.AudioRouteSnapshot
import com.duiyi.android.domain.AudioSessionConfiguring
import com.duiyi.android.domain.AudioUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioSessionPolicy @Inject constructor(
    @ApplicationContext context: Context,
) : AudioSessionConfiguring {

    private val manager = context.getSystemService(AudioManager::class.java)

    override suspend fun activate(useCase: AudioUseCase) {
        when (useCase) {
            AudioUseCase.FaceToFace, AudioUseCase.InterpretationCaptions -> {
                manager.mode = AudioManager.MODE_NORMAL
                manager.isSpeakerphoneOn = false
            }
            AudioUseCase.InterpretationVoiceOut -> {
                manager.mode = AudioManager.MODE_NORMAL
                manager.isSpeakerphoneOn = false
            }
        }
    }

    override suspend fun deactivate() {
        manager.mode = AudioManager.MODE_NORMAL
    }

    override fun currentRoute(): AudioRouteSnapshot = AudioRouteSnapshot(canSpeakWithoutEcho())

    override fun routeChanges(): Flow<AudioRouteSnapshot> = callbackFlow {
        trySend(currentRoute())
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                trySend(currentRoute())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                trySend(currentRoute())
            }
        }
        manager.registerAudioDeviceCallback(callback, null)
        awaitClose { manager.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged()

    private fun canSpeakWithoutEcho(): Boolean {
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { it.type.isHeadphoneLike() }
    }

    private fun Int.isHeadphoneLike(): Boolean = this == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        this == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        this == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        this == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        this == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        this == AudioDeviceInfo.TYPE_USB_HEADSET ||
        this == AudioDeviceInfo.TYPE_USB_DEVICE ||
        this == AudioDeviceInfo.TYPE_HEARING_AID ||
        this == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
}
