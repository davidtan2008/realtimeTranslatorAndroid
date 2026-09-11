package com.duiyi.android.domain

import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class TranslationPairStatus { Installed, Supported, Unsupported }

data class LanguageCapability(
    val speechLocale: String,
    val ttsVoice: String,
    val speech: Boolean,
    val translate: Boolean,
    val speak: Boolean,
)

interface LanguageCataloging {
    val all: List<AppLanguage>
    fun capability(language: AppLanguage): LanguageCapability
    suspend fun speechSupported(language: AppLanguage): Boolean
    suspend fun translationPair(from: AppLanguage, to: AppLanguage): TranslationPairStatus
    suspend fun refresh()
}

enum class TaskStatus { Running, PausedListening, Ended }

data class TranscriptEntry(
    val id: UUID = UUID.randomUUID(),
    val speaker: SpeakerSide,
    val source: AppLanguage,
    val sourceText: String,
    val translatedText: String,
    val isFinal: Boolean,
    val recordedAt: Instant = Instant.now(),
)

enum class SpeakerSide {
    User,
    Peer,
    ;

    val opposite: SpeakerSide get() = if (this == User) Peer else User
}

data class ConversationSummary(
    val text: String,
    val generatedOnDevice: Boolean,
)

data class TaskSession(
    val id: UUID,
    val feature: FeatureID,
    val status: TaskStatus,
    val startedAt: Instant,
    val endedAt: Instant?,
    val title: String,
    val subtitle: String? = null,
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage?,
    val entries: List<TranscriptEntry> = emptyList(),
    val summary: ConversationSummary? = null,
) {
    val duration: Duration
        get() = Duration.between(startedAt, endedAt ?: Instant.now())

    val hasTranscript: Boolean
        get() = entries.any { it.sourceText.isNotBlank() }
}

object TaskTitle {
    fun make(
        feature: FeatureID,
        source: AppLanguage,
        target: AppLanguage?,
        language: AppLanguage,
        startedAt: Instant = Instant.now(),
    ): String {
        val date = formatDate(startedAt, language)
        val module = Copy.featureTitle(feature, language)
        val body = when (feature) {
            FeatureID.FaceToFace -> {
                val pair = target ?: source.defaultPeer
                "$module · ${source.nativeName} ↔ ${pair.nativeName}"
            }
            FeatureID.Simultaneous -> {
                val pair = target ?: source.defaultPeer
                "$module · ${source.nativeName} → ${pair.nativeName}"
            }
            FeatureID.Meeting -> "$module · ${source.nativeName}"
        }
        return "$date $body"
    }

    private fun formatDate(instant: Instant, language: AppLanguage): String {
        val local = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return if (language == AppLanguage.ZhHans) {
            "${local.monthValue}月${local.dayOfMonth}日"
        } else {
            java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH).format(local)
        }
    }

    fun subtitle(from: List<TranscriptEntry>): String? {
        val first = from.firstOrNull { it.sourceText.isNotBlank() } ?: return null
        val text = first.sourceText.trim()
        return if (text.length <= 16) text else text.take(16)
    }
}

const val MEETING_TRANSCRIPT_LIMIT = 20_000

interface TaskStore {
    suspend fun activeTask(forFeature: FeatureID): TaskSession?
    suspend fun unfinishedTasks(): List<TaskSession>
    suspend fun startNew(feature: FeatureID, source: AppLanguage, target: AppLanguage?): TaskSession
    suspend fun append(taskId: UUID, entries: List<TranscriptEntry>)
    suspend fun setStatus(taskId: UUID, status: TaskStatus)
    suspend fun saveSummary(taskId: UUID, summary: ConversationSummary)
    suspend fun end(taskId: UUID, summary: ConversationSummary?): TaskSession
    suspend fun list(filter: FeatureID?): List<TaskSession>
    suspend fun task(id: UUID): TaskSession?
    suspend fun delete(id: UUID)
}

enum class SpeechRecognitionStyle { LiveCaptions, MeetingNotes }

data class Utterance(
    val id: UUID,
    val language: AppLanguage,
    val text: String,
    val isFinal: Boolean,
)

interface SpeechRecognizing {
    suspend fun start(
        languages: List<AppLanguage>,
        fallback: AppLanguage,
        style: SpeechRecognitionStyle,
    ): Flow<Utterance>
    suspend fun stop()
}

enum class SummaryAvailability {
    Available,
    DeviceNotEligible,
    ModelNotReady,
    Unavailable,
}

interface ConversationSummarizing {
    suspend fun availability(): SummaryAvailability
    suspend fun summarize(entries: List<TranscriptEntry>, outputLanguage: AppLanguage): ConversationSummary
}

interface ExportSharing {
    fun makeTranscriptFile(task: TaskSession, language: AppLanguage): File
}

enum class AudioUseCase { FaceToFace, InterpretationCaptions, InterpretationVoiceOut }

data class AudioRouteSnapshot(val canSpeakWithoutEcho: Boolean)

interface AudioSessionConfiguring {
    suspend fun activate(useCase: AudioUseCase)
    suspend fun deactivate()
    fun currentRoute(): AudioRouteSnapshot
    fun routeChanges(): Flow<AudioRouteSnapshot>
}

interface SpeechSynthesizing {
    suspend fun enqueue(text: String, language: AppLanguage)
    suspend fun stopAndClear()
    suspend fun hasVoice(language: AppLanguage): Boolean
}

interface Translating {
    suspend fun pairStatus(from: AppLanguage, to: AppLanguage): TranslationPairStatus
    suspend fun prepare(pairs: List<Pair<AppLanguage, AppLanguage>>, onProgress: (Float) -> Unit)
    suspend fun translate(
        from: AppLanguage,
        to: AppLanguage,
        text: String,
        previousSource: String = "",
    ): String
    suspend fun installedLanguages(): Set<AppLanguage>
    suspend fun downloadLanguage(language: AppLanguage)
    suspend fun download(onProgress: (Float) -> Unit = {})
    suspend fun release()
    suspend fun deleteLanguage(language: AppLanguage)
}

interface PermissionGranting {
    suspend fun currentStatus(): PermissionStatus
    suspend fun requestAccess(): PermissionStatus
}

interface DeviceCapabilityProbing {
    suspend fun probe(): DeviceCapabilities
}

data class TranslationJob(
    val id: UUID = UUID.randomUUID(),
    val utteranceId: UUID,
    val from: AppLanguage,
    val to: AppLanguage,
    val text: String,
    val isFinal: Boolean,
    val generation: Long,
    val previousSource: String = "",
)
