package com.duiyi.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedAssetRulesTest {
    @Test
    fun downloadingWinsOverPartialFiles() {
        assertEquals(
            ManagedAssetStatus.Downloading,
            ManagedAssetRules.status(ready = false, running = true, failed = false, bytes = 12_000),
        )
    }

    @Test
    fun failedKeepsRetryStateWhenIncomplete() {
        assertEquals(
            ManagedAssetStatus.Failed,
            ManagedAssetRules.status(ready = false, running = false, failed = true, bytes = 4_000),
        )
    }

    @Test
    fun partialWhenSomeBytesExist() {
        assertEquals(
            ManagedAssetStatus.Partial,
            ManagedAssetRules.status(ready = false, running = false, failed = false, bytes = 8_000),
        )
    }

    @Test
    fun snapshotFlagsMissingAndBusy() {
        val missingSpeech = ManagedAsset(
            id = ManagedAssetId.SpeechPack,
            status = ManagedAssetStatus.Missing,
            bytes = 0,
            expectedBytes = 100,
            progress = 0f,
        )
        val downloadingPack = ManagedAsset(
            id = ManagedAssetId.TranslationModel,
            status = ManagedAssetStatus.Downloading,
            bytes = 0,
            expectedBytes = 0,
            progress = 0f,
            indeterminate = true,
        )
        val snapshot = DeviceAssetSnapshot(
            loaded = true,
            speech = missingSpeech,
            translations = listOf(downloadingPack),
            translationAvailable = true,
        )
        assertTrue(snapshot.hasMissing)
        assertTrue(snapshot.downloading)
        assertTrue(missingSpeech.canDownload)
        assertFalse(downloadingPack.canDelete)
        assertFalse(missingSpeech.canDelete)
    }
}
