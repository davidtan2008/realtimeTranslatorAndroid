package com.duiyi.android.domain.routing

import com.duiyi.android.domain.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UtteranceAssemblerTest {
    @Test
    fun chineseUtteranceLocksWinner() {
        val assembler = UtteranceAssembler()
        val result = assembler.ingest(AppLanguage.ZhHans, "我们六点见", isFinal = true)
        assertNotNull(result)
        assertEquals(AppLanguage.ZhHans, result!!.language)
        assertEquals("我们六点见", result.text)
    }

    @Test
    fun englishRejectedWhenClaimedChineseAndMostlyLatin() {
        val assembler = UtteranceAssembler()
        val result = assembler.ingest(AppLanguage.ZhHans, "hello there friend", isFinal = false)
        assertEquals(null, result)
    }

    @Test
    fun finalAllowsOppositeLanguageNext() {
        val assembler = UtteranceAssembler()
        assembler.ingest(AppLanguage.ZhHans, "你的名字是什么", isFinal = true)
        val next = assembler.ingest(AppLanguage.English, "today is sunny", isFinal = false)
        assertNotNull(next)
        assertEquals(AppLanguage.English, next!!.language)
        assertEquals("today is sunny", next.text)
    }
}
