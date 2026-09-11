package com.duiyi.android.services.speech

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.duiyi.android.app.ForegroundActivity
import com.duiyi.android.domain.DuiyiError
import com.duiyi.android.services.DuiyiLog

data class RecognizerBinding(
    val engine: SpeechRecognizer,
    val preferOffline: Boolean,
    val source: String,
)

data class RecognizerCandidate(
    val source: String,
    val component: ComponentName?,
    val onDevice: Boolean,
)

object SpeechRecognizerFactory {
    val GOOGLE: ComponentName = ComponentName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
    )

    fun hasGoogleApp(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(GOOGLE.packageName, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun googleMicGranted(context: Context): Boolean =
        context.packageManager.checkPermission(
            Manifest.permission.RECORD_AUDIO,
            GOOGLE.packageName,
        ) == PackageManager.PERMISSION_GRANTED

    fun googleAppSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", GOOGLE.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun defaultRecognitionService(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, "voice_recognition_service")

    fun defaultServicePackage(context: Context): String? =
        defaultRecognitionService(context)?.substringBefore('/')

    fun defaultIsVendorMute(context: Context): Boolean {
        val pkg = defaultServicePackage(context) ?: return false
        return SpeechEnginePolicy.isVendorMuteSpeechPackage(pkg)
    }

    fun defaultIsGoogle(context: Context): Boolean {
        val pkg = defaultServicePackage(context) ?: return false
        return SpeechEnginePolicy.isGoogleSpeechPackage(pkg)
    }

    fun googleAppLaunchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(GOOGLE.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun voiceInputSettingsIntent(): Intent =
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun fixIntent(error: DuiyiError?, context: Context): Intent? = when (error) {
        DuiyiError.GoogleMicrophoneDenied -> googleAppSettingsIntent()
        DuiyiError.HonorRecognizerBlocked -> voiceInputSettingsIntent()
        DuiyiError.SpeechEngineSilent -> googleAppLaunchIntent(context)
        else -> null
    }

    fun listedComponents(context: Context): List<ComponentName> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        return context.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL).mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            ComponentName(service.packageName, service.name)
        }
    }

    fun candidates(
        context: Context,
        honorFamily: Boolean,
        allowOnDevice: Boolean,
    ): List<RecognizerCandidate> {
        val out = mutableListOf<RecognizerCandidate>()
        if (allowOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            out += RecognizerCandidate(source = "on-device", component = null, onDevice = true)
        }
        if (honorFamily) {
            if (hasGoogleApp(context)) {
                if (defaultIsGoogle(context)) {
                    out += RecognizerCandidate(source = "default-google", component = null, onDevice = false)
                }
                out += RecognizerCandidate(source = "google", component = GOOGLE, onDevice = false)
            }
            return out
        }
        val listed = listedComponents(context)
        val rankedPackages = SpeechEnginePolicy.rankSpeechPackages(
            listed.map { it.packageName }.distinct(),
            honorFamily,
        )
        rankedPackages.forEach { pkg ->
            listed.filter { it.packageName == pkg }.forEach { component ->
                val source = if (SpeechEnginePolicy.isGoogleSpeechPackage(pkg)) {
                    "google:${component.className.substringAfterLast('.')}"
                } else {
                    "service:${pkg.substringAfterLast('.')}"
                }
                out += RecognizerCandidate(source = source, component = component, onDevice = false)
            }
        }
        if (hasGoogleApp(context) && out.none { it.component?.packageName == GOOGLE.packageName }) {
            out += RecognizerCandidate(source = "google", component = GOOGLE, onDevice = false)
        }
        if (!honorFamily && SpeechRecognizer.isRecognitionAvailable(context) &&
            out.none { !it.onDevice && it.component == null }
        ) {
            out += RecognizerCandidate(source = "default", component = null, onDevice = false)
        }
        return out.distinctBy { Triple(it.onDevice, it.component?.packageName, it.component?.className) }
    }

    fun create(
        context: Context,
        candidate: RecognizerCandidate,
        preferOffline: Boolean,
    ): RecognizerBinding? {
        val host = ForegroundActivity.hostContext(context)
        DuiyiLog.i("speech create source=${candidate.source} host=${host.javaClass.simpleName}")
        val engine = runCatching {
            when {
                candidate.onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(host)
                candidate.component != null -> SpeechRecognizer.createSpeechRecognizer(host, candidate.component)
                else -> SpeechRecognizer.createSpeechRecognizer(host)
            }
        }.onFailure {
            DuiyiLog.w("recognizer create failed source=${candidate.source}", it)
        }.getOrNull() ?: return null
        return RecognizerBinding(engine, preferOffline = preferOffline && candidate.onDevice, source = candidate.source)
    }

    fun listedRecognitionServices(context: Context): List<String> =
        listedComponents(context).map { "${it.packageName}/${it.className}" }
}
