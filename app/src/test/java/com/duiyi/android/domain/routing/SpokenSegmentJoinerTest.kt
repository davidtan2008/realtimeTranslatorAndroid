package com.duiyi.android.domain.routing

import com.duiyi.android.domain.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenSegmentJoinerTest {
    @Test
    fun shortEnglishFinalsStayOpenThenJoin() {
        val joiner = SpokenSegmentJoiner()
        val first = joiner.ingest(0L, AppLanguage.English, "PRACTICE", true)
        assertEquals(1, first.size)
        assertFalse(first.single().isFinal)
        assertEquals("Practice", first.single().text)

        val second = joiner.ingest(200_000_000L, AppLanguage.English, "PASS", true)
        assertEquals(1, second.size)
        assertFalse(second.single().isFinal)
        assertEquals("Practice Pass", second.single().text)
    }

    @Test
    fun longSentenceFlushesImmediately() {
        val joiner = SpokenSegmentJoiner()
        val result = joiner.ingest(
            0L,
            AppLanguage.English,
            "I like your board I did not know you were good at it",
            true,
        )
        assertEquals(1, result.size)
        assertTrue(result.single().isFinal)
        assertTrue(result.single().text.startsWith("I like your board"))
    }

    @Test
    fun timeoutFlushesHeldShort() {
        val joiner = SpokenSegmentJoiner()
        joiner.ingest(0L, AppLanguage.English, "OKAY", true)
        val flushed = joiner.tick(SpokenSegmentJoiner.HOLD_NS)
        assertEquals("Okay", flushed!!.text)
        assertTrue(flushed.isFinal)
    }

    @Test
    fun chineseShortsJoinWithoutSpace() {
        val joiner = SpokenSegmentJoiner()
        joiner.ingest(0L, AppLanguage.ZhHans, "实践", true)
        val next = joiner.ingest(100_000_000L, AppLanguage.ZhHans, "通过", true)
        assertEquals("实践通过", next.single().text)
        assertFalse(next.single().isFinal)
    }

    @Test
    fun newSentenceDoesNotSwallowHeldFragment() {
        val joiner = SpokenSegmentJoiner()
        joiner.ingest(0L, AppLanguage.English, "OKAY", true)
        val result = joiner.ingest(
            200_000_000L,
            AppLanguage.English,
            "I like your board I did not know you were good at it",
            true,
        )
        assertEquals(2, result.size)
        assertEquals("Okay", result[0].text)
        assertTrue(result[0].isFinal)
        assertTrue(result[1].isFinal)
        assertTrue(result[1].text.startsWith("I like your board"))
    }
}
