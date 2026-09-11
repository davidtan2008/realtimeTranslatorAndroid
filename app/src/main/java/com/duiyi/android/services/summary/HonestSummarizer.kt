package com.duiyi.android.services.summary

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.ConversationSummarizing
import com.duiyi.android.domain.ConversationSummary
import com.duiyi.android.domain.SummaryAvailability
import com.duiyi.android.domain.TranscriptEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Honor and most phones have no Gemini Nano / AICore.
 * Never pretend they can "just turn it on". A later build can swap this
 * implementation when GenAI APIs are present.
 */
@Singleton
class HonestSummarizer @Inject constructor() : ConversationSummarizing {
    override suspend fun availability(): SummaryAvailability = SummaryAvailability.DeviceNotEligible

    override suspend fun summarize(
        entries: List<TranscriptEntry>,
        outputLanguage: AppLanguage,
    ): ConversationSummary {
        error("summarize called while device is not eligible")
    }
}
