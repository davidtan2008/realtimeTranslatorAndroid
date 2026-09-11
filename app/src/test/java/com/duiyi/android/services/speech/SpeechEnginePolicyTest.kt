package com.duiyi.android.services.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEnginePolicyTest {
    @Test
    fun honorFamilyIncludesMagicOsBrands() {
        assertTrue(SpeechEnginePolicy.isHonorFamily("HONOR", "honor"))
        assertTrue(SpeechEnginePolicy.isHonorFamily("HUAWEI", "HONOR"))
        assertFalse(SpeechEnginePolicy.isHonorFamily("Google", "google"))
    }

    @Test
    fun honorNeverWaitsOnMissingOfflinePacks() {
        assertFalse(SpeechEnginePolicy.preferOffline(honorFamily = true, usingOnDevice = false, packsInstalled = false))
        assertFalse(SpeechEnginePolicy.preferOffline(honorFamily = true, usingOnDevice = true, packsInstalled = true))
    }

    @Test
    fun honorDoesNotUseOnDeviceStub() {
        assertFalse(
            SpeechEnginePolicy.useOnDeviceRecognizer(
                honorFamily = true,
                onDeviceAvailable = true,
                installedLocales = emptySet(),
            ),
        )
    }

    @Test
    fun pixelUsesOnDeviceWhenPacksExist() {
        assertTrue(
            SpeechEnginePolicy.useOnDeviceRecognizer(
                honorFamily = false,
                onDeviceAvailable = true,
                installedLocales = setOf("zh-CN", "en-US"),
            ),
        )
        assertTrue(SpeechEnginePolicy.preferOffline(honorFamily = false, usingOnDevice = true, packsInstalled = true))
    }

    @Test
    fun languageSwitchStaysOffOnHonor() {
        assertFalse(SpeechEnginePolicy.enableLanguageSwitch("HONOR", "honor"))
        assertTrue(SpeechEnginePolicy.enableLanguageSwitch("Google", "google"))
    }

    @Test
    fun honorRanksGoogleAheadAndDropsVendorEngines() {
        val ranked = SpeechEnginePolicy.rankSpeechPackages(
            listOf(
                "com.hihonor.vassistant",
                "com.google.android.googlequicksearchbox",
                "com.example.other",
            ),
            honorFamily = true,
        )
        assertEquals(
            listOf("com.google.android.googlequicksearchbox"),
            ranked,
        )
    }

    @Test
    fun pixelKeepsVendorAsLastResort() {
        val ranked = SpeechEnginePolicy.rankSpeechPackages(
            listOf("com.huawei.hivoice", "com.google.android.googlequicksearchbox"),
            honorFamily = false,
        )
        assertEquals(
            listOf("com.google.android.googlequicksearchbox", "com.huawei.hivoice"),
            ranked,
        )
    }

    @Test
    fun magicVoiceCountsAsVendorMute() {
        assertTrue(SpeechEnginePolicy.isVendorMuteSpeechPackage("com.hihonor.magicvoice"))
        assertTrue(
            SpeechEnginePolicy.isVendorMuteSpeechPackage(
                "com.hihonor.magicvoice/.voiceui.service.MagicVoiceRecognitionService".substringBefore('/'),
            ),
        )
        assertFalse(SpeechEnginePolicy.isVendorMuteSpeechPackage("com.google.android.googlequicksearchbox"))
    }

    @Test
    fun honorUsesSherpaNotGoogleSpeechIntent() {
        assertFalse(SpeechEnginePolicy.useGoogleSpeechIntent(honorFamily = true))
        assertFalse(SpeechEnginePolicy.useGoogleSpeechIntent(honorFamily = false))
        assertTrue(SpeechEnginePolicy.useSherpaOnDevice(honorFamily = true))
        assertFalse(SpeechEnginePolicy.useSherpaOnDevice(honorFamily = false))
    }

    @Test
    fun honorDoesNotUseSilenceWindowExtras() {
        assertFalse(SpeechEnginePolicy.useSilenceWindowExtras(honorFamily = true))
        assertTrue(SpeechEnginePolicy.useSilenceWindowExtras(honorFamily = false))
    }
}
