package com.duiyi.android.services.persistence

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.FeatureID
import com.duiyi.android.domain.SpeakerSide
import com.duiyi.android.domain.TranscriptEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryTaskStoreTest {
    @Test
    fun emptyTaskDoesNotEnterHistory() = runTest {
        val store = InMemoryTaskStore()
        val task = store.startNew(FeatureID.FaceToFace, AppLanguage.ZhHans, AppLanguage.English)
        store.end(task.id, null)
        assertTrue(store.list(null).isEmpty())
        assertNull(store.task(task.id))
    }

    @Test
    fun deletingOneTaskLeavesTheOther() = runTest {
        val store = InMemoryTaskStore()
        val first = store.startNew(FeatureID.FaceToFace, AppLanguage.ZhHans, AppLanguage.English)
        store.append(
            first.id,
            listOf(
                TranscriptEntry(
                    speaker = SpeakerSide.User,
                    source = AppLanguage.ZhHans,
                    sourceText = "你好",
                    translatedText = "Hello",
                    isFinal = true,
                ),
            ),
        )
        store.end(first.id, null)
        val second = store.startNew(FeatureID.Meeting, AppLanguage.ZhHans, null)
        store.append(
            second.id,
            listOf(
                TranscriptEntry(
                    speaker = SpeakerSide.User,
                    source = AppLanguage.ZhHans,
                    sourceText = "开会",
                    translatedText = "",
                    isFinal = true,
                ),
            ),
        )
        store.end(second.id, null)
        store.delete(first.id)
        val remaining = store.list(null)
        assertEquals(1, remaining.size)
        assertEquals(second.id, remaining.first().id)
        assertEquals("开会", remaining.first().entries.first().sourceText)
    }
}
