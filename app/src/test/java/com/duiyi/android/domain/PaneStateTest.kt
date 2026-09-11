package com.duiyi.android.domain

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneStateTest {
    @Test
    fun continuationMergeKeepsTranslation() {
        val id = UUID.randomUUID()
        val first = CaptionLine(
            id = id,
            side = SpeakerSide.User,
            source = AppLanguage.English,
            sourceText = "How are you",
            phase = CaptionLine.Phase.Streaming,
        )
        var pane = PaneState.Empty.applyLive(first)
        pane = pane.applyTranslation(id, "你好吗", false)
        val continued = first.copy(
            sourceText = "How are you today.",
            phase = CaptionLine.Phase.Settled,
        )
        pane = pane.applyLive(continued)
        pane = pane.applyTranslation(id, "你今天好吗。", true)
        val visible = pane.live?.translatedText ?: pane.history.lastOrNull()?.translatedText
        assertEquals("你今天好吗。", visible)
        assertTrue(pane.history.size + (if (pane.live != null) 1 else 0) >= 1)
    }

    @Test
    fun newUtteranceArchivesPreviousWithTranslation() {
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        var pane = PaneState.Empty.applyLive(
            CaptionLine(
                id = firstId,
                side = SpeakerSide.Peer,
                source = AppLanguage.ZhHans,
                sourceText = "这个也不对呀。",
                phase = CaptionLine.Phase.Settled,
            ),
        )
        pane = pane.applyTranslation(firstId, "This isn't right either.", true)
        pane = pane.applyLive(
            CaptionLine(
                id = secondId,
                side = SpeakerSide.Peer,
                source = AppLanguage.ZhHans,
                sourceText = "你好你好。",
                phase = CaptionLine.Phase.Settled,
            ),
        )
        assertEquals("This isn't right either.", pane.history.last().translatedText)
        assertEquals("你好你好。", pane.live?.sourceText)
    }
}
