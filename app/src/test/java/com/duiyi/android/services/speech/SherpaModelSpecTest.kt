package com.duiyi.android.services.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaModelSpecTest {
    @Test
    fun senseVoicePackHasModelTokensAndVad() {
        assertEquals(3, SherpaModelSpec.parts.size)
        assertTrue(SherpaModelSpec.parts.any { it.fileName == "model.int8.onnx" })
        assertTrue(SherpaModelSpec.parts.any { it.fileName == "tokens.txt" })
        assertTrue(SherpaModelSpec.parts.any { it.fileName == "silero_vad.onnx" })
        assertFalse(SherpaModelSpec.parts.any { it.fileName.contains("encoder") })
    }

    @Test
    fun incompleteDownloadsAreRejected() {
        assertFalse(SherpaModelSpec.isComplete("model.int8.onnx", 92))
        assertTrue(SherpaModelSpec.isComplete("tokens.txt", 20_000))
        assertTrue(SherpaModelSpec.isComplete("silero_vad.onnx", 643_854))
        assertFalse(SherpaModelSpec.isComplete("unknown.bin", 99_000_000))
    }

    @Test
    fun mirrorsPreferChinaThenUpstream() {
        val model = SherpaModelSpec.mirrors("model.int8.onnx")
        assertTrue(model.first().contains("hf-mirror.com"))
        assertTrue(model.any { it.contains("huggingface.co") })
        assertTrue(model.all { it.endsWith("/model.int8.onnx") })
        val vad = SherpaModelSpec.mirrors("silero_vad.onnx")
        assertTrue(vad.any { it.contains("silero_vad.onnx") })
    }

    @Test
    fun expectedSizesCoverSenseVoiceAndVad() {
        assertEquals(239_000_000L + 316_000L + 643_854L, SherpaModelSpec.expectedTotalBytes)
        assertTrue(SherpaModelSpec.parts.all { it.expectedBytes >= it.minBytes })
    }
}

class SenseVoiceTextTest {
    @Test
    fun stripsLeftoverSenseVoiceTags() {
        assertEquals(
            "我喜欢你的冲浪板",
            SenseVoiceText.strip("<|zh|> <|NEUTRAL|> <|Speech|> 我喜欢你的冲浪板"),
        )
    }

    @Test
    fun mapsSenseVoiceLangTags() {
        assertEquals(
            com.duiyi.android.domain.AppLanguage.English,
            SenseVoiceText.languageFrom("<|en|>", "hello", com.duiyi.android.domain.AppLanguage.ZhHans),
        )
        assertEquals(
            com.duiyi.android.domain.AppLanguage.ZhHans,
            SenseVoiceText.languageFrom("yue", "你好", com.duiyi.android.domain.AppLanguage.English),
        )
    }
}
