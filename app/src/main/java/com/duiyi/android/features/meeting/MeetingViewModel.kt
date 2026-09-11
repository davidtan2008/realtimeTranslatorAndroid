package com.duiyi.android.features.meeting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duiyi.android.app.PreferencesStore
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.AudioSessionConfiguring
import com.duiyi.android.domain.AudioUseCase
import com.duiyi.android.domain.CaptionFontSize
import com.duiyi.android.domain.CaptionLine
import com.duiyi.android.domain.ConversationSummarizing
import com.duiyi.android.domain.ConversationSummary
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.DuiyiError
import com.duiyi.android.domain.FeatureID
import com.duiyi.android.domain.LanguageCataloging
import com.duiyi.android.domain.MEETING_TRANSCRIPT_LIMIT
import com.duiyi.android.domain.PaneState
import com.duiyi.android.domain.PermissionGranting
import com.duiyi.android.domain.PermissionStatus
import com.duiyi.android.domain.SessionState
import com.duiyi.android.domain.SpeakerSide
import com.duiyi.android.domain.SpeechRecognitionStyle
import com.duiyi.android.domain.SpeechRecognizing
import com.duiyi.android.domain.SpokenText
import com.duiyi.android.domain.SummaryAvailability
import com.duiyi.android.domain.TaskSession
import com.duiyi.android.domain.TaskSessionController
import com.duiyi.android.domain.TaskStore
import com.duiyi.android.domain.TranscriptEntry
import com.duiyi.android.domain.Translating
import com.duiyi.android.domain.TranslationPolicy
import com.duiyi.android.domain.Utterance
import com.duiyi.android.services.speech.SpeechEngineException
import com.duiyi.android.services.speech.SpeechIntentBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MeetingUiState(
    val session: SessionState = SessionState.Idle,
    val sourceLanguage: AppLanguage = AppLanguage.ZhHans,
    val displayLanguage: AppLanguage = AppLanguage.ZhHans,
    val fontSize: CaptionFontSize = CaptionFontSize.Standard,
    val pane: PaneState = PaneState.Empty,
    val entries: List<TranscriptEntry> = emptyList(),
    val hasActiveTask: Boolean = false,
    val taskStartedAt: Instant? = null,
    val error: DuiyiError? = null,
    val lengthLimited: Boolean = false,
    val settingsOpen: Boolean = false,
    val summaryOpen: Boolean = false,
    val summarizing: Boolean = false,
    val summary: ConversationSummary? = null,
    val summaryAvailability: SummaryAvailability = SummaryAvailability.Unavailable,
    val summaryMessage: String? = null,
    val confirmEnd: Boolean = false,
    val confirmReplace: Boolean = false,
    val confirmLeave: Boolean = false,
)

@HiltViewModel
class MeetingViewModel @Inject constructor(
    private val speech: SpeechRecognizing,
    private val permissions: PermissionGranting,
    private val preferences: PreferencesStore,
    private val summarizer: ConversationSummarizing,
    private val audioSession: AudioSessionConfiguring,
    private val speechIntent: SpeechIntentBridge,
    private val translator: Translating,
    val catalog: LanguageCataloging,
    taskStore: TaskStore,
) : ViewModel() {
    private val task = TaskSessionController(FeatureID.Meeting, taskStore)
    private val _state = MutableStateFlow(
        MeetingUiState(
            sourceLanguage = preferences.meetingLanguage,
            displayLanguage = preferences.userLanguage,
            fontSize = preferences.fontSize,
        ),
    )
    val state: StateFlow<MeetingUiState> = _state.asStateFlow()

    private var speechJob: Job? = null
    private var persistJob: Job? = null
    private var idleJob: Job? = null
    private var isStopping = false
    private var holdScreenAfterPause = false
    private val aliases = mutableMapOf<UUID, UUID>()
    private var transcript = listOf<TranscriptEntry>()

    val keepsScreenAwake: Boolean
        get() = _state.value.session == SessionState.Listening || holdScreenAfterPause

    init {
        viewModelScope.launch { bootstrap() }
    }

    fun controlLabel(): String {
        val s = _state.value
        if (!s.hasActiveTask) return Copy.needNewTask(s.displayLanguage)
        return when (s.session) {
            SessionState.Listening -> Copy.recording(s.displayLanguage)
            SessionState.Paused -> Copy.paused(s.displayLanguage)
            SessionState.Preparing -> Copy.preparing(s.displayLanguage)
            else -> Copy.tapToStart(s.displayLanguage)
        }
    }

    private suspend fun bootstrap() {
        translator.release()
        _state.update { it.copy(session = SessionState.Preparing) }
        task.restore()?.let { restore(it) }
        val denied = permissions.currentStatus().asDuiyiError()
        _state.update {
            it.copy(
                session = if (denied == null) SessionState.Ready else SessionState.Failed,
                error = denied,
                hasActiveTask = task.hasActiveTask,
                taskStartedAt = task.startedAt,
            )
        }
    }

    fun requestNewTask() {
        if (task.hasActiveTask) _state.update { it.copy(confirmReplace = true) }
        else viewModelScope.launch { beginTask() }
    }

    fun confirmReplace(yes: Boolean) {
        _state.update { it.copy(confirmReplace = false) }
        if (yes) viewModelScope.launch { beginTask() }
    }

    fun requestEndTask() {
        if (task.hasActiveTask) _state.update { it.copy(confirmEnd = true) }
    }

    fun confirmEnd(yes: Boolean) {
        _state.update { it.copy(confirmEnd = false) }
        if (yes) viewModelScope.launch { endTask() }
    }

    fun requestLeave() {
        if (task.hasActiveTask) _state.update { it.copy(confirmLeave = true) }
    }

    fun confirmLeave(yes: Boolean, onLeave: () -> Unit) {
        _state.update { it.copy(confirmLeave = false) }
        if (yes) viewModelScope.launch {
            endTask()
            onLeave()
        }
    }

    fun toggleListening() {
        if (_state.value.lengthLimited) return
        viewModelScope.launch {
            if (!task.hasActiveTask) {
                beginTask()
                startListening()
                return@launch
            }
            when (_state.value.session) {
                SessionState.Listening -> stopListening()
                SessionState.Preparing -> Unit
                else -> startListening()
            }
        }
    }

    fun pauseForBackground() {
        if (speechIntent.launching || speechIntent.sessionActive) return
        if (_state.value.session == SessionState.Listening) viewModelScope.launch { stopListening() }
    }

    fun openSettings(open: Boolean) = _state.update { it.copy(settingsOpen = open) }

    fun setSourceLanguage(language: AppLanguage) {
        preferences.meetingLanguage = language
        _state.update {
            it.copy(
                sourceLanguage = language,
                pane = it.pane.archiveLive().withHint(null),
                session = if (it.session == SessionState.Listening || it.session == SessionState.Paused) SessionState.Ready else it.session,
            )
        }
    }

    fun setFontSize(size: CaptionFontSize) {
        preferences.fontSize = size
        _state.update { it.copy(fontSize = size) }
    }

    fun requestSummary() {
        viewModelScope.launch {
            val availability = summarizer.availability()
            val language = _state.value.displayLanguage
            val message = if (transcript.none { it.sourceText.isNotBlank() }) {
                DuiyiError.SummaryEmpty.userMessage(language)
            } else {
                Copy.summaryAvailabilityMessage(availability, language)
            }
            _state.update {
                it.copy(
                    settingsOpen = false,
                    summaryOpen = true,
                    summarizing = false,
                    summaryAvailability = availability,
                    summaryMessage = message,
                )
            }
        }
    }

    fun dismissSummary() = _state.update { it.copy(summaryOpen = false) }

    private suspend fun beginTask() {
        if (task.hasActiveTask) {
            stopListening(false)
            task.finish(transcript, _state.value.summary)
        }
        resetCaptions()
        task.beginNew(_state.value.sourceLanguage, null)
        _state.update {
            it.copy(hasActiveTask = true, taskStartedAt = task.startedAt, session = SessionState.Ready, error = null, lengthLimited = false)
        }
    }

    private suspend fun endTask() {
        stopListening(false)
        task.finish(transcript, _state.value.summary)
        resetCaptions()
        _state.update {
            it.copy(hasActiveTask = false, taskStartedAt = null, session = SessionState.Ready, pane = PaneState.Empty, entries = emptyList(), lengthLimited = false)
        }
    }

    private suspend fun startListening() {
        if (!task.hasActiveTask ||
            _state.value.lengthLimited ||
            _state.value.session == SessionState.Listening ||
            _state.value.session == SessionState.Preparing
        ) {
            return
        }
        isStopping = false
        idleJob?.cancel()
        holdScreenAfterPause = false
        var status = permissions.currentStatus()
        if (status != PermissionStatus.Granted) status = permissions.requestAccess()
        status.asDuiyiError()?.let { error ->
            _state.update { it.copy(session = SessionState.Failed, error = error) }
            return
        }
        val language = _state.value.sourceLanguage
        _state.update { it.copy(session = SessionState.Preparing, error = null) }
        runCatching {
            translator.release()
            audioSession.activate(AudioUseCase.InterpretationCaptions)
            speechJob?.cancel()
            speechJob = null
            speech.stop()
            val flow = speech.start(listOf(language), language, SpeechRecognitionStyle.MeetingNotes)
            task.setListening(true)
            _state.update { it.copy(session = SessionState.Listening, error = null) }
            speechJob = viewModelScope.launch {
                try {
                    flow.collect { accept(it) }
                    if (!isStopping) {
                        _state.update { it.copy(session = SessionState.Failed, error = DuiyiError.RecognitionInterrupted) }
                    }
                } catch (_: CancellationException) {
                } catch (error: Exception) {
                    if (!isStopping) {
                        _state.update { it.copy(session = SessionState.Failed, error = SpeechEngineException.of(error)) }
                    }
                }
            }
        }.onFailure { error ->
            _state.update { it.copy(session = SessionState.Failed, error = SpeechEngineException.of(error)) }
        }
    }

    private suspend fun stopListening(restoreIdle: Boolean = true) {
        isStopping = true
        speechJob?.cancel()
        speechJob = null
        speech.stop()
        if (_state.value.session == SessionState.Listening) {
            task.setListening(false)
            persistNow()
            _state.update { it.copy(session = SessionState.Paused) }
            if (restoreIdle) {
                holdScreenAfterPause = true
                idleJob = viewModelScope.launch {
                    delay(TranslationPolicy.IDLE_RESTORE_MS)
                    holdScreenAfterPause = false
                }
            } else {
                holdScreenAfterPause = false
            }
        }
        _state.update { it.copy(pane = it.pane.withHint(null)) }
    }

    private fun accept(utterance: Utterance) {
        if (!task.hasActiveTask || _state.value.lengthLimited) return
        val text = utterance.text.trim()
        if (text.isEmpty()) return
        val spoken = _state.value.sourceLanguage
        val existing = _state.value.pane.live
        val captionId = when {
            existing != null && existing.source == spoken &&
                SpokenText.isContinuation(text, existing.sourceText, spoken) -> {
                aliases[utterance.id] = existing.id
                existing.id
            }
            else -> utterance.id
        }
        val line = CaptionLine(
            id = captionId,
            side = SpeakerSide.User,
            source = spoken,
            sourceText = text,
            phase = if (utterance.isFinal) CaptionLine.Phase.Settled else CaptionLine.Phase.Streaming,
        )
        val applied = _state.value.pane.applyLive(line).withHint(if (utterance.isFinal) null else Copy.speaking(spoken))
        _state.update { it.copy(pane = applied) }
        upsertTranscript(applied.live ?: line)
        if (line.phase == CaptionLine.Phase.Settled) {
            schedulePersist()
            if (transcript.sumOf { it.sourceText.length } >= MEETING_TRANSCRIPT_LIMIT) {
                _state.update { it.copy(lengthLimited = true) }
                viewModelScope.launch { stopListening(false) }
            }
        }
    }

    private fun upsertTranscript(line: CaptionLine) {
        val index = transcript.indexOfFirst { it.id == line.id }
        if (index >= 0) {
            val current = transcript[index]
            transcript = transcript.toMutableList().also {
                it[index] = current.copy(
                    sourceText = SpokenText.preferredSource(current.sourceText, line.sourceText),
                    isFinal = line.phase == CaptionLine.Phase.Settled,
                )
            }
        } else {
            val last = transcript.lastOrNull()
            val continues = last != null &&
                last.source == line.source &&
                !last.isFinal &&
                SpokenText.isContinuation(line.sourceText, last.sourceText, line.source)
            if (continues) {
                transcript = transcript.dropLast(1) + last.copy(
                    sourceText = SpokenText.preferredSource(last.sourceText, line.sourceText),
                    isFinal = line.phase == CaptionLine.Phase.Settled,
                )
            } else {
                transcript = transcript + TranscriptEntry(
                    id = line.id,
                    speaker = SpeakerSide.User,
                    source = line.source,
                    sourceText = line.sourceText,
                    translatedText = "",
                    isFinal = line.phase == CaptionLine.Phase.Settled,
                )
            }
        }
        _state.update { it.copy(entries = transcript) }
    }

    private fun restore(session: TaskSession) {
        transcript = session.entries
        var pane = PaneState.Empty
        session.entries.takeLast(PaneState.HISTORY_LIMIT).forEach { entry ->
            pane = pane.copy(
                history = pane.history + CaptionLine(
                    id = entry.id,
                    side = SpeakerSide.User,
                    source = entry.source,
                    sourceText = entry.sourceText,
                    translatedText = entry.translatedText,
                    phase = CaptionLine.Phase.Settled,
                ),
            )
        }
        _state.update {
            it.copy(pane = pane, entries = transcript, summary = session.summary, hasActiveTask = true, taskStartedAt = session.startedAt)
        }
    }

    private fun resetCaptions() {
        transcript = emptyList()
        aliases.clear()
        _state.update { it.copy(pane = PaneState.Empty, entries = emptyList(), summary = null, summaryMessage = null, lengthLimited = false) }
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(TranslationPolicy.PERSIST_MS)
            persistNow()
        }
    }

    private suspend fun persistNow() {
        task.persist(transcript)
    }
}
