package com.duiyi.android.services.translation

import com.duiyi.android.domain.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateGemmaPromptTest {
    @Test
    fun promptMatchesOfficialTranslatorTemplate() {
        val prompt = TranslateGemmaPrompt.build(
            from = AppLanguage.English,
            to = AppLanguage.ZhHans,
            text = "I like your board",
            previousSource = "The waves were good today",
        )
        assertTrue(prompt.contains("<start_of_turn>user"))
        assertTrue(prompt.contains("<start_of_turn>model"))
        assertTrue(prompt.contains(" (en) to "))
        assertTrue(prompt.contains(" (zh-Hans)"))
        assertFalse(prompt.contains("[context]"))
        assertFalse(prompt.contains("The waves were good today"))
        assertTrue(prompt.contains("I like your board"))
        assertTrue(prompt.contains("You are a professional English"))
        assertFalse(prompt.contains("\"type\":\"text\""))
        val split = TranslateGemmaPrompt.parts(
            from = AppLanguage.English,
            to = AppLanguage.ZhHans,
            text = "I like your board",
        )
        assertEquals(prompt, split.prefix + split.suffix)
        assertFalse(split.prefix.contains("I like your board"))
        assertTrue(split.suffix.startsWith("I like your board"))
        assertEquals(
            split.prefix,
            TranslateGemmaPrompt.prefix(AppLanguage.English, AppLanguage.ZhHans),
        )
    }

    @Test
    fun blankPreviousOmitsContextBlock() {
        val prompt = TranslateGemmaPrompt.build(
            from = AppLanguage.ZhHans,
            to = AppLanguage.English,
            text = "我喜欢你的冲浪板",
        )
        assertFalse(prompt.contains("[context]"))
        assertTrue(prompt.contains("我喜欢你的冲浪板"))
        assertTrue(prompt.contains(" (zh-Hans) to "))
        assertTrue(prompt.contains(" (en)"))
    }

    @Test
    fun sanitizeDropsNotesAndQuotes() {
        assertEquals(
            "我喜欢你的冲浪板",
            TranslateGemmaPrompt.sanitize("\"我喜欢你的冲浪板\"\nNote: casual tone"),
        )
        assertEquals(
            "I like your board",
            TranslateGemmaPrompt.sanitize("Translation: I like your board"),
        )
        assertEquals(
            "你好",
            TranslateGemmaPrompt.sanitize("你好<end_of_turn>"),
        )
        assertEquals(
            "它非常可爱。",
            TranslateGemmaPrompt.sanitize("[背景] 它非常可爱。"),
        )
        assertEquals(
            "There are many trees in the park.",
            TranslateGemmaPrompt.sanitize("[Context] There are many trees in the park."),
        )
        assertEquals(
            "公园里有很多树。",
            TranslateGemmaPrompt.sanitize("[语境] 公园里有很多树。"),
        )
    }

    @Test
    fun maxOutputTokensScalesWithSourceLength() {
        assertEquals(18, TranslateGemmaPrompt.maxOutputTokens("Hi"))
        assertEquals(28, TranslateGemmaPrompt.maxOutputTokens("How are you."))
        assertEquals(23, TranslateGemmaPrompt.maxOutputTokens("这个也不对呀。"))
        assertEquals(64, TranslateGemmaPrompt.maxOutputTokens("a".repeat(200)))
        assertTrue(TranslateGemmaPrompt.maxOutputTokens("It's very cute.") <= TranslateGemmaPrompt.MAX_OUTPUT_TOKENS)
    }
}

class GemmaModelSpecTest {
    @Test
    fun q4FileIsAboutTwoPointFiveGigabytes() {
        assertEquals("translategemma-4b-it.Q4_K_M.gguf", GemmaModelSpec.FILE_NAME)
        assertEquals(2_489_909_760L, GemmaModelSpec.EXPECTED_BYTES)
        assertTrue(GemmaModelSpec.isComplete(GemmaModelSpec.EXPECTED_BYTES))
        assertFalse(GemmaModelSpec.isComplete(1_000_000L))
        assertTrue(GemmaModelSpec.mirrors.first().contains("hf-mirror.com"))
    }
}
