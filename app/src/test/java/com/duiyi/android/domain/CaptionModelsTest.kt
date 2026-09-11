package com.duiyi.android.domain

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionModelsTest {
    @Test
    fun settledTranslationReplacesLongerPartial() {
        val id = UUID.randomUUID()
        val live = CaptionLine(
            id = id,
            side = SpeakerSide.Peer,
            source = AppLanguage.English,
            sourceText = "I didn't know.",
            translatedText = "我没有这样做。",
            phase = CaptionLine.Phase.Streaming,
        )
        val pane = PaneState(live = live)
        val next = pane.applyTranslation(id, "我不知道。", settle = true)
        assertEquals("我不知道。", next.live?.translatedText)
        assertEquals(CaptionLine.Phase.Settled, next.live?.phase)
    }

    @Test
    fun streamingStillPrefersLongerTranslation() {
        assertEquals(
            "我没有这样做。",
            SpokenText.preferredTranslation("我没有这样做。", "我没有", settle = false),
        )
        assertEquals(
            "我不知道。",
            SpokenText.preferredTranslation("我没有这样做。", "我不知道。", settle = true),
        )
    }
}
