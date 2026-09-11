package com.duiyi.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextNormalizeTest {
    @Test
    fun allCapsEnglishBecomesSentenceCase() {
        assertEquals(
            "Late in the union after swimming",
            SpokenText.normalizeAsr("LATE IN THE UNION AFTER SWIMMING", AppLanguage.English),
        )
    }

    @Test
    fun chineseIsUnchanged() {
        assertEquals("我喜欢你的冲浪板", SpokenText.normalizeAsr("我喜欢你的冲浪板", AppLanguage.ZhHans))
    }

    @Test
    fun mixedCaseEnglishStays() {
        assertEquals(
            "I like your board",
            SpokenText.normalizeAsr("I like your board", AppLanguage.English),
        )
    }

    @Test
    fun mismatchedEnglishIsDroppedForChinesePrimary() {
        assertFalse(SpokenText.matchesPrimaryScript("PRACTICE PASS IN", AppLanguage.ZhHans))
        assertTrue(SpokenText.matchesPrimaryScript("我们六点见", AppLanguage.ZhHans))
    }
}

class TranslationPolicyTest {
    @Test
    fun fragmentsAreNotSentenceLike() {
        assertFalse(TranslationPolicy.isSentenceLike("Practice", AppLanguage.English))
        assertFalse(TranslationPolicy.isSentenceLike("实践", AppLanguage.ZhHans))
        assertTrue(TranslationPolicy.isSentenceLike("I like your board I did not know you were good at it", AppLanguage.English))
        assertTrue(TranslationPolicy.isSentenceLike("我喜欢你的冲浪板，不知道你还擅长这个。", AppLanguage.ZhHans))
    }
}
