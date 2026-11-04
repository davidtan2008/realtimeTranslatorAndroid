package com.duiyi.android.domain.routing

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class StablePrefixTrackerTest {
    private val id: UUID = UUID.randomUUID()

    @Test
    fun firstHypothesisCommitsNothing() {
        val tracker = StablePrefixTracker()
        assertEquals("", tracker.update(id, "我觉得这个方案，", false))
    }

    @Test
    fun commitsUpToBoundaryOnceTwoHypothesesAgree() {
        val tracker = StablePrefixTracker()
        tracker.update(id, "我觉得这个方案，", false)
        val chunk = tracker.update(id, "我觉得这个方案，不太行", false)
        assertEquals("我觉得这个方案，", chunk)
    }

    @Test
    fun doesNotCommitTwice() {
        val tracker = StablePrefixTracker()
        tracker.update(id, "我觉得这个方案，", false)
        assertEquals("我觉得这个方案，", tracker.update(id, "我觉得这个方案，不太行", false))
        // Same agreed prefix, nothing new past the boundary yet.
        assertEquals("", tracker.update(id, "我觉得这个方案，不太行吧", false))
    }

    @Test
    fun withoutPunctuationNothingIsCommitted() {
        val tracker = StablePrefixTracker()
        tracker.update(id, "我觉得这个方案", false)
        assertEquals("", tracker.update(id, "我觉得这个方案不太行", false))
        assertEquals("", tracker.update(id, "我觉得这个方案不太行啊", false))
    }

    @Test
    fun revisedHypothesisShrinksTheAgreedRegion() {
        val tracker = StablePrefixTracker()
        tracker.update(id, "我觉得这个方案，", false)
        // SenseVoice changed its mind about the middle; the boundary is no longer agreed.
        assertEquals("", tracker.update(id, "我觉得那个方案，不太行", false))
    }

    @Test
    fun finalCommitsEverythingRemaining() {
        val tracker = StablePrefixTracker()
        tracker.update(id, "我觉得这个方案，", false)
        assertEquals("我觉得这个方案，", tracker.update(id, "我觉得这个方案，不太行", false))
        assertEquals("不太行", tracker.update(id, "我觉得这个方案，不太行", true))
    }

    @Test
    fun finalOnFirstHypothesisCommitsItAll() {
        val tracker = StablePrefixTracker()
        assertEquals("好的。", tracker.update(id, "好的。", true))
    }

    @Test
    fun aLonePunctuationChunkIsHeldBack() {
        val tracker = StablePrefixTracker(minChunkChars = 2)
        tracker.update(id, "，", false)
        // A bare "，" is below the threshold, so it cannot go out on its own.
        assertEquals("", tracker.update(id, "，好的，", false))
        // It rides along with the next chunk instead.
        assertEquals("，好的，", tracker.update(id, "，好的，对吧", false))
    }

    @Test
    fun aCharacterPlusPunctuationClearsTheThreshold() {
        val tracker = StablePrefixTracker(minChunkChars = 2)
        tracker.update(id, "嗯，", false)
        assertEquals("嗯，", tracker.update(id, "嗯，好，", false))
    }

    @Test
    fun utterancesAreTrackedSeparately() {
        val other: UUID = UUID.randomUUID()
        val tracker = StablePrefixTracker()
        tracker.update(id, "第一句，", false)
        tracker.update(other, "另一句，", false)
        assertEquals("第一句，", tracker.update(id, "第一句，第二句", false))
        assertEquals("另一句，", tracker.update(other, "另一句，再说", false))
    }

    @Test
    fun forgetDropsCommittedState() {
        val tracker = StablePrefixTracker()
        tracker.update(id, "好的，", false)
        assertEquals("好的，", tracker.update(id, "好的，明白", false))
        tracker.forget(id)
        assertEquals("", tracker.update(id, "好的，明白", false))
    }
}
