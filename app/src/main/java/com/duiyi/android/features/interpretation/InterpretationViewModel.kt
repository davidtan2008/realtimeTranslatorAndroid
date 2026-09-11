package com.duiyi.android.features.interpretation

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
import com.duiyi.android.domain.PaneState
import com.duiyi.android.domain.PermissionGranting
import com.duiyi.android.domain.PermissionStatus
import com.duiyi.android.domain.SessionState
import com.duiyi.android.domain.SpeakerSide
import com.duiyi.android.domain.SpeechRecognitionStyle
import com.duiyi.android.domain.SpeechRecognizing
import com.duiyi.android.domain.SpeechSynthesizing
import com.duiyi.android.domain.SpokenText
import com.duiyi.android.domain.SummaryAvailability
import com.duiyi.android.domain.TaskSession
import com.duiyi.android.domain.TaskSessionController
import com.duiyi.android.domain.TaskStore
import com.duiyi.android.domain.TranscriptEntry
import com.duiyi.android.domain.Translating
import com.duiyi.android.domain.TranslationJob
import com.duiyi.android.domain.TranslationPolicy
import com.duiyi.android.domain.Utterance
import com.duiyi.android.domain.routing.ClauseSplitter
import com.duiyi.android.services.speech.SpeechEngineException
import com.duiyi.android.services.speech.SpeechIntentBridge
import com.duiyi.android.services.translation.TranslationPump
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.ArrayDeque
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

data class InterpretationUiState(
    val session: SessionState = SessionState.Idle,
    val sourceLanguage: AppLanguage = AppLanguage.English,
    val targetLanguage: AppLanguage = AppLanguage.ZhHans,
    val fontSize: CaptionFontSize = CaptionFontSize.Standard,
    val pane: PaneState = PaneState.Empty,
    val hasActiveTask: Boolean = false,
    val taskStartedAt: Instant? = null,
    val error: DuiyiError? = null,
    val voiceOutEnabled: Boolean = false,
    val showsSourceLine: Boolean = true,
    val canSpeakWithoutEcho: Boolean = false,
    val voiceOutBlocked: String? = null,
    val speakingOut: Boolean = false,
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
class InterpretationViewModel @Inject constructor(
    private val speech: SpeechRecognizing,
    private val permissions: PermissionGranting,
    private val preferences: PreferencesStore,
    private val summarizer: ConversationSummarizing,
    private val audioSession: AudioSessionConfiguring,
    private val synthesizer: SpeechSynthesizing,
    private val translator: Translating,
    private val speechIntent: SpeechIntentBridge,
    val catalog: LanguageCataloging,
    taskStore: TaskStore,
) : ViewModel() {
    private val task = TaskSessionController(FeatureID.Simultaneous, taskStore)
    private val _state = MutableStateFlow(
        InterpretationUiState(
            sourceLanguage = preferences.sourceLanguage,
            targetLanguage = preferences.targetLanguage,
            fontSize = preferences.fontSize,
            voiceOutEnabled = preferences.voiceOutEnabled,
            showsSourceLine = preferences.showsSourceLine,
        ),
    )
    val state: StateFlow<InterpretationUiState> = _state.asStateFlow()

    private var speechJob: Job? = null
    private val debounceJobs = mutableMapOf<UUID, Job>()
    private var persistJob: Job? = null
    private var idleJob: Job? = null
    private var routeJob: Job? = null
    private var isStopping = false
    private var holdScreenAfterPause = false
    private var translationGeneration = 0L
    private val lastAppliedSourceCount = mutableMapOf<UUID, Int>()
    private val sentSourceText = mutableMapOf<UUID, String>()
    private val lastFinalSource = mutableMapOf<String, String>()
    private val aliases = mutableMapOf<UUID, UUID>()
    private lateinit var translationPump: TranslationPump
    private val spokenPrefixes = mutableMapOf<UUID, Int>()
    private val speakQueue = ArrayDeque<String>()
    private var speaking = false
    private var transcript = listOf<TranscriptEntry>()

    val keepsScreenAwake: Boolean
        get() = _state.value.session == SessionState.Listening || holdScreenAfterPause

    init {
        translationPump = TranslationPump(translator, viewModelScope, ::fulfill)
        viewModelScope.launch { bootstrap() }
    }

    fun controlLabel(): String {
        val s = _state.value
        if (!s.hasActiveTask) return Copy.needNewTask(s.targetLanguage)
        return when (s.session) {
            SessionState.Listening -> Copy.listening(s.targetLanguage)
            SessionState.Paused -> Copy.paused(s.targetLanguage)
            SessionState.Preparing -> Copy.preparing(s.targetLanguage)
            else -> Copy.tapToStart(s.targetLanguage)
        }
    }

    fun voiceOutLabel(): String {
        val s = _state.value
        s.voiceOutBlocked?.let { return it }
        return if (s.voiceOutEnabled && s.speakingOut) {
            Copy.voiceOutSpeaking(s.targetLanguage)
        } else {
            Copy.voiceOut(s.targetLanguage)
        }
    }

    private suspend fun bootstrap() {
        observeRoute()
        val route = audioSession.currentRoute()
        if (_state.value.voiceOutEnabled && !route.canSpeakWithoutEcho) {
            preferences.voiceOutEnabled = false
            _state.update { it.copy(voiceOutEnabled = false, canSpeakWithoutEcho = false) }
        } else {
            _state.update { it.copy(canSpeakWithoutEcho = route.canSpeakWithoutEcho) }
        }
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

    private fun observeRoute() {
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            audioSession.routeChanges().collect { snapshot ->
                val enabled = _state.value.voiceOutEnabled
                if (enabled && !snapshot.canSpeakWithoutEcho) {
                    synthesizer.stopAndClear()
                    speakQueue.clear()
                    speaking = false
                    preferences.voiceOutEnabled = false
                    _state.update {
                        it.copy(
                            canSpeakWithoutEcho = false,
                            voiceOutEnabled = false,
                            speakingOut = false,
                            voiceOutBlocked = DuiyiError.VoiceOutNeedsHeadphones.userMessage(it.targetLanguage),
                        )
                    }
                } else {
                    _state.update { it.copy(canSpeakWithoutEcho = snapshot.canSpeakWithoutEcho) }
                }
            }
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

    fun confirmLeave(yes: Boolean, onLeave: () -> Unit) {
        _state.update { it.copy(confirmLeave = false) }
        if (yes) viewModelScope.launch {
            endTask()
            onLeave()
        }
    }

    fun requestLeave() {
        if (task.hasActiveTask) _state.update { it.copy(confirmLeave = true) }
    }

    fun toggleListening() {
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

    fun swapDirection() {
        val s = _state.value
        if (s.sourceLanguage == s.targetLanguage) return
        preferences.sourceLanguage = s.targetLanguage
        preferences.targetLanguage = s.sourceLanguage
        resetLive()
        _state.update { it.copy(sourceLanguage = s.targetLanguage, targetLanguage = s.sourceLanguage) }
    }

    fun setSourceLanguage(language: AppLanguage) {
        val s = _state.value
        if (language == s.sourceLanguage) return
        var target = s.targetLanguage
        if (language == target) {
            target = s.sourceLanguage
            preferences.targetLanguage = target
        }
        preferences.sourceLanguage = language
        resetLive()
        _state.update { it.copy(sourceLanguage = language, targetLanguage = target) }
    }

    fun setTargetLanguage(language: AppLanguage) {
        val s = _state.value
        if (language == s.targetLanguage) return
        var source = s.sourceLanguage
        if (language == source) {
            source = s.targetLanguage
            preferences.sourceLanguage = source
        }
        preferences.targetLanguage = language
        resetLive()
        _state.update { it.copy(targetLanguage = language, sourceLanguage = source) }
    }

    fun setFontSize(size: CaptionFontSize) {
        preferences.fontSize = size
        _state.update { it.copy(fontSize = size) }
    }

    fun setShowsSource(enabled: Boolean) {
        preferences.showsSourceLine = enabled
        _state.update { it.copy(showsSourceLine = enabled) }
    }

    fun setVoiceOut(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                synthesizer.stopAndClear()
                speakQueue.clear()
                speaking = false
                preferences.voiceOutEnabled = false
                _state.update { it.copy(voiceOutEnabled = false, speakingOut = false, voiceOutBlocked = null) }
                return@launch
            }
            val language = _state.value.targetLanguage
            if (!synthesizer.hasVoice(language)) {
                _state.update { it.copy(voiceOutEnabled = false, voiceOutBlocked = DuiyiError.SynthesisVoiceMissing.userMessage(language)) }
                return@launch
            }
            audioSession.activate(AudioUseCase.InterpretationVoiceOut)
            delay(200)
            val ok = audioSession.currentRoute().canSpeakWithoutEcho
            if (!ok) {
                preferences.voiceOutEnabled = false
                _state.update {
                    it.copy(
                        voiceOutEnabled = false,
                        canSpeakWithoutEcho = false,
                        voiceOutBlocked = DuiyiError.VoiceOutNeedsHeadphones.userMessage(language),
                    )
                }
                return@launch
            }
            preferences.voiceOutEnabled = true
            _state.update { it.copy(voiceOutEnabled = true, canSpeakWithoutEcho = true, voiceOutBlocked = null) }
        }
    }

    fun requestSummary() {
        viewModelScope.launch {
            val availability = summarizer.availability()
            val language = _state.value.targetLanguage
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
        val s = _state.value
        task.beginNew(s.sourceLanguage, s.targetLanguage)
        _state.update {
            it.copy(hasActiveTask = true, taskStartedAt = task.startedAt, session = SessionState.Ready, error = null)
        }
    }

    private suspend fun endTask() {
        stopListening(false)
        task.finish(transcript, _state.value.summary)
        resetCaptions()
        _state.update {
            it.copy(hasActiveTask = false, taskStartedAt = null, session = SessionState.Ready, pane = PaneState.Empty)
        }
    }

    private suspend fun startListening() {
        if (!task.hasActiveTask ||
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
        val s = _state.value
        _state.update { it.copy(session = SessionState.Preparing, error = null) }
        val useCase = if (s.voiceOutEnabled && s.canSpeakWithoutEcho) {
            AudioUseCase.InterpretationVoiceOut
        } else {
            AudioUseCase.InterpretationCaptions
        }
        runCatching {
            audioSession.activate(useCase)
            translator.prepare(listOf(s.sourceLanguage to s.targetLanguage)) {}
            speechJob?.cancel()
            speechJob = null
            speech.stop()
            val flow = speech.start(listOf(s.sourceLanguage), s.sourceLanguage, SpeechRecognitionStyle.LiveCaptions)
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
        synthesizer.stopAndClear()
        speakQueue.clear()
        speaking = false
        speech.stop()
        _state.update { it.copy(speakingOut = false) }
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
        val s = _state.value
        val text = utterance.text.trim()
        if (text.isEmpty()) return
        val existing = s.pane.live
        val captionId: UUID
        val carried: String
        when {
            existing != null && existing.source == utterance.language &&
                SpokenText.isContinuation(text, existing.sourceText, utterance.language) -> {
                captionId = existing.id
                carried = existing.translatedText
                aliases[utterance.id] = existing.id
            }
            else -> {
                captionId = utterance.id
                carried = if (existing?.id == utterance.id) existing.translatedText else ""
            }
        }
        val line = CaptionLine(
            id = captionId,
            side = SpeakerSide.User,
            source = utterance.language,
            sourceText = text,
            translatedText = carried,
            phase = if (utterance.isFinal) CaptionLine.Phase.Settled else CaptionLine.Phase.Streaming,
        )
        val applied = s.pane.applyLive(line).withHint(if (utterance.isFinal) null else Copy.speaking(s.sourceLanguage))
        _state.update { it.copy(pane = applied) }
        upsertTranscript(applied.live ?: line)
        enqueueTranslation(applied.live ?: line, utterance.isFinal)
    }

    private fun enqueueTranslation(line: CaptionLine, isFinal: Boolean) {
        val text = line.sourceText.trim()
        if (text.isEmpty()) return
        if (!isFinal) return
        if (sentSourceText[line.id] == text) return
        val s = _state.value
        val from = if (SpokenText.scriptIsDecisive(text)) {
            SpokenText.inferFromScript(text, s.sourceLanguage)
        } else {
            line.source
        }
        val to = s.targetLanguage
        val previous = lastFinalSource["${from.translateTag}-${to.translateTag}"].orEmpty()
            .let { if (it == text) "" else it }
        val job = TranslationJob(
            utteranceId = line.id,
            from = from,
            to = to,
            text = text,
            isFinal = true,
            generation = ++translationGeneration,
            previousSource = previous,
        )
        debounceJobs.remove(line.id)?.cancel()
        sentSourceText[line.id] = text
        translationPump.submit(job)
    }

    private fun fulfill(job: TranslationJob, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val id = aliases[job.utteranceId] ?: job.utteranceId
        val appliedCount = lastAppliedSourceCount[id] ?: 0
        if (!job.isFinal && appliedCount > job.text.length) return
        lastAppliedSourceCount[id] = maxOf(appliedCount, job.text.length)
        if (job.isFinal) lastFinalSource["${job.from.translateTag}-${job.to.translateTag}"] = job.text
        _state.update { it.copy(pane = it.pane.applyTranslation(id, trimmed, job.isFinal)) }
        transcript = transcript.map { entry ->
            if (entry.id == id) entry.copy(translatedText = trimmed, isFinal = entry.isFinal || job.isFinal) else entry
        }
        if (job.isFinal) {
            schedulePersist()
            enqueueSpeech(id, trimmed)
        }
    }

    private fun enqueueSpeech(utteranceId: UUID, translated: String) {
        val s = _state.value
        if (!s.voiceOutEnabled || !s.canSpeakWithoutEcho) return
        val already = spokenPrefixes[utteranceId] ?: 0
        val (clauses, prefix) = ClauseSplitter.consume(utteranceId, translated, already, s.targetLanguage, true)
        spokenPrefixes[utteranceId] = prefix
        clauses.forEach { clause ->
            if (speakQueue.size >= TranslationPolicy.SPEECH_QUEUE_LIMIT) speakQueue.removeFirst()
            speakQueue.addLast(clause.text)
        }
        pumpSpeech()
    }

    private fun pumpSpeech() {
        if (speaking) return
        if (speakQueue.isEmpty()) {
            _state.update { it.copy(speakingOut = false) }
            return
        }
        val next = speakQueue.removeFirst()
        speaking = true
        _state.update { it.copy(speakingOut = true) }
        val language = _state.value.targetLanguage
        viewModelScope.launch {
            synthesizer.enqueue(next, language)
            speaking = false
            pumpSpeech()
        }
    }

    private fun upsertTranscript(line: CaptionLine) {
        val index = transcript.indexOfFirst { it.id == line.id }
        if (index >= 0) {
            val current = transcript[index]
            transcript = transcript.toMutableList().also {
                it[index] = current.copy(
                    sourceText = SpokenText.preferredSource(current.sourceText, line.sourceText),
                    translatedText = line.translatedText.ifEmpty { current.translatedText },
                    isFinal = line.phase == CaptionLine.Phase.Settled,
                )
            }
        } else {
            transcript = transcript + TranscriptEntry(
                id = line.id,
                speaker = SpeakerSide.User,
                source = line.source,
                sourceText = line.sourceText,
                translatedText = line.translatedText,
                isFinal = line.phase == CaptionLine.Phase.Settled,
            )
        }
        if (line.phase == CaptionLine.Phase.Settled) schedulePersist()
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
            it.copy(pane = pane.copy(history = pane.history.takeLast(PaneState.HISTORY_LIMIT)), summary = session.summary, hasActiveTask = true, taskStartedAt = session.startedAt)
        }
    }

    private fun resetLive() {
        synthesizer.let { }
        viewModelScope.launch { synthesizer.stopAndClear() }
        speakQueue.clear()
        _state.update {
            it.copy(
                pane = it.pane.archiveLive().withHint(null),
                session = if (it.session == SessionState.Listening || it.session == SessionState.Paused) SessionState.Ready else it.session,
            )
        }
    }

    private fun resetCaptions() {
        transcript = emptyList()
        lastAppliedSourceCount.clear()
        sentSourceText.clear()
        lastFinalSource.clear()
        aliases.clear()
        spokenPrefixes.clear()
        speakQueue.clear()
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
        speaking = false
        viewModelScope.launch { synthesizer.stopAndClear() }
        _state.update { it.copy(pane = PaneState.Empty, summary = null, summaryMessage = null, speakingOut = false) }
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
