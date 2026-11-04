package com.duiyi.android.features.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duiyi.android.app.PreferencesStore
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.AudioUseCase
import com.duiyi.android.domain.AudioSessionConfiguring
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
import com.duiyi.android.domain.TranslationJob
import com.duiyi.android.domain.TranslationPolicy
import com.duiyi.android.domain.Utterance
import com.duiyi.android.domain.routing.LanguageRouter
import com.duiyi.android.domain.routing.StablePrefixTracker
import com.duiyi.android.domain.routing.UtteranceAssembler
import com.duiyi.android.services.ComputeGate
import com.duiyi.android.services.LatencyProbe
import com.duiyi.android.services.speech.SpeechEngineException
import com.duiyi.android.services.speech.SpeechIntentBridge
import com.duiyi.android.services.translation.TranslationPump
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

data class ConversationUiState(
    val session: SessionState = SessionState.Idle,
    val userLanguage: AppLanguage = AppLanguage.ZhHans,
    val peerLanguage: AppLanguage = AppLanguage.English,
    val fontSize: CaptionFontSize = CaptionFontSize.Standard,
    val userPane: PaneState = PaneState.Empty,
    val peerPane: PaneState = PaneState.Empty,
    val autoStartListening: Boolean = true,
    val incrementalTranslation: Boolean = false,
    val hasActiveTask: Boolean = false,
    val taskStartedAt: Instant? = null,
    val error: DuiyiError? = null,
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
class ConversationViewModel @Inject constructor(
    private val speech: SpeechRecognizing,
    private val permissions: PermissionGranting,
    private val preferences: PreferencesStore,
    private val summarizer: ConversationSummarizing,
    private val audioSession: AudioSessionConfiguring,
    private val translator: Translating,
    private val speechIntent: SpeechIntentBridge,
    private val probe: LatencyProbe,
    private val computeGate: ComputeGate,
    val catalog: LanguageCataloging,
    taskStore: TaskStore,
) : ViewModel() {
    private val task = TaskSessionController(FeatureID.FaceToFace, taskStore)
    private val _state = MutableStateFlow(
        ConversationUiState(
            userLanguage = preferences.userLanguage,
            peerLanguage = preferences.peerLanguage,
            fontSize = preferences.fontSize,
            autoStartListening = preferences.autoStartListening,
            incrementalTranslation = preferences.incrementalTranslation,
        ),
    )
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private var speechJob: Job? = null
    private var persistJob: Job? = null
    private var idleJob: Job? = null
    private var isStopping = false
    private var translationGeneration = 0L
    private var holdScreenAfterPause = false
    private val routedSide = mutableMapOf<UUID, SpeakerSide>()
    private val lastAppliedSourceCount = mutableMapOf<UUID, Int>()
    private val sentSourceText = mutableMapOf<UUID, String>()
    private val lastFinalSource = mutableMapOf<String, String>()
    private val aliases = mutableMapOf<UUID, UUID>()
    private lateinit var translationPump: TranslationPump
    private var lastCommittedLanguage: AppLanguage? = null
    private var lastCommittedAt = 0L
    private var transcript = listOf<TranscriptEntry>()
    private val stablePrefix = StablePrefixTracker()
    private val committedChunks = mutableMapOf<UUID, MutableList<String>>()

    val keepsScreenAwake: Boolean
        get() = _state.value.session == SessionState.Listening || holdScreenAfterPause

    init {
        translationPump = TranslationPump(
            translator = translator,
            scope = viewModelScope,
            probe = probe,
            gate = computeGate,
            onResult = ::fulfill,
            onProgress = ::fulfillProgress,
        )
        viewModelScope.launch { bootstrap() }
    }

    fun controlLabel(): String {
        val s = _state.value
        if (!s.hasActiveTask) return Copy.needNewTask(s.userLanguage)
        return when (s.session) {
            SessionState.Listening -> Copy.listening(s.userLanguage)
            SessionState.Paused -> Copy.paused(s.userLanguage)
            SessionState.Preparing -> Copy.preparing(s.userLanguage)
            else -> Copy.tapToStart(s.userLanguage)
        }
    }

    private suspend fun bootstrap() {
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
        if (task.hasActiveTask) {
            _state.update { it.copy(confirmReplace = true) }
        } else {
            viewModelScope.launch { beginTask() }
        }
    }

    fun confirmReplace(yes: Boolean) {
        _state.update { it.copy(confirmReplace = false) }
        if (yes) viewModelScope.launch { beginTask() }
    }

    fun requestEndTask() {
        if (!task.hasActiveTask) return
        _state.update { it.copy(confirmEnd = true) }
    }

    fun confirmEnd(yes: Boolean) {
        _state.update { it.copy(confirmEnd = false) }
        if (yes) viewModelScope.launch { endTask() }
    }

    fun requestLeave() {
        if (task.hasActiveTask) {
            _state.update { it.copy(confirmLeave = true) }
        }
    }

    fun confirmLeave(yes: Boolean, onLeave: () -> Unit) {
        _state.update { it.copy(confirmLeave = false) }
        if (yes) {
            viewModelScope.launch {
                endTask()
                onLeave()
            }
        }
    }

    fun toggleListening() {
        viewModelScope.launch {
            if (!task.hasActiveTask) {
                beginTask()
                if (_state.value.session != SessionState.Listening &&
                    _state.value.session != SessionState.Preparing
                ) {
                    startListening()
                }
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
        if (_state.value.session == SessionState.Listening) {
            viewModelScope.launch { stopListening() }
        }
    }

    fun openSettings(open: Boolean) = _state.update { it.copy(settingsOpen = open) }

    fun swapLanguages() {
        val s = _state.value
        if (s.userLanguage == s.peerLanguage) return
        preferences.userLanguage = s.peerLanguage
        preferences.peerLanguage = s.userLanguage
        resetCaptions()
        _state.update {
            it.copy(userLanguage = s.peerLanguage, peerLanguage = s.userLanguage)
        }
    }

    fun setUserLanguage(language: AppLanguage) {
        val s = _state.value
        if (language == s.userLanguage) return
        var peer = s.peerLanguage
        if (language == peer) {
            peer = s.userLanguage
            preferences.peerLanguage = peer
        }
        preferences.userLanguage = language
        resetCaptions()
        _state.update { it.copy(userLanguage = language, peerLanguage = peer) }
    }

    fun setPeerLanguage(language: AppLanguage) {
        val s = _state.value
        if (language == s.peerLanguage) return
        var user = s.userLanguage
        if (language == user) {
            user = s.peerLanguage
            preferences.userLanguage = user
        }
        preferences.peerLanguage = language
        resetCaptions()
        _state.update { it.copy(peerLanguage = language, userLanguage = user) }
    }

    fun setFontSize(size: CaptionFontSize) {
        preferences.fontSize = size
        _state.update { it.copy(fontSize = size) }
    }

    fun setAutoStart(enabled: Boolean) {
        preferences.autoStartListening = enabled
        _state.update { it.copy(autoStartListening = enabled) }
    }

    fun setIncremental(enabled: Boolean) {
        preferences.incrementalTranslation = enabled
        // Mid-utterance switching would mix whole-sentence and fragment translations.
        resetTranslationTracking()
        _state.update { it.copy(incrementalTranslation = enabled) }
    }

    fun clearCaptions() {
        resetCaptions()
        publishPanes()
    }

    fun requestSummary() {
        viewModelScope.launch {
            val availability = summarizer.availability()
            val language = _state.value.userLanguage
            if (transcript.none { it.sourceText.isNotBlank() }) {
                _state.update {
                    it.copy(
                        summaryOpen = true,
                        summarizing = false,
                        summaryAvailability = availability,
                        summaryMessage = DuiyiError.SummaryEmpty.userMessage(language),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    settingsOpen = false,
                    summaryOpen = true,
                    summarizing = availability == SummaryAvailability.Available,
                    summaryAvailability = availability,
                    summaryMessage = if (availability == SummaryAvailability.Available) {
                        Copy.summaryLoading(language)
                    } else {
                        Copy.summaryAvailabilityMessage(availability, language)
                    },
                    summary = null,
                )
            }
            if (availability == SummaryAvailability.Available) {
                runCatching { summarizer.summarize(transcript, language) }
                    .onSuccess { summary ->
                        task.saveSummary(summary)
                        _state.update {
                            it.copy(summarizing = false, summary = summary, summaryMessage = Copy.nanoGenerated(language))
                        }
                    }
                    .onFailure {
                        _state.update {
                            it.copy(
                                summarizing = false,
                                summaryMessage = DuiyiError.SummaryEmpty.userMessage(language),
                            )
                        }
                    }
            }
        }
    }

    fun dismissSummary() = _state.update { it.copy(summaryOpen = false) }

    private suspend fun beginTask() {
        if (task.hasActiveTask) {
            stopListening(restoreIdle = false)
            task.finish(transcript, _state.value.summary)
        }
        resetCaptions()
        val s = _state.value
        task.beginNew(s.userLanguage, s.peerLanguage)
        _state.update {
            it.copy(
                hasActiveTask = true,
                taskStartedAt = task.startedAt,
                session = SessionState.Ready,
                error = null,
            )
        }
        if (s.autoStartListening) startListening()
    }

    private suspend fun endTask() {
        stopListening(restoreIdle = false)
        task.finish(transcript, _state.value.summary)
        resetCaptions()
        _state.update {
            it.copy(
                hasActiveTask = false,
                taskStartedAt = null,
                session = SessionState.Ready,
                userPane = PaneState.Empty,
                peerPane = PaneState.Empty,
            )
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
        if (status != com.duiyi.android.domain.PermissionStatus.Granted) {
            status = permissions.requestAccess()
        }
        status.asDuiyiError()?.let { error ->
            _state.update { it.copy(session = SessionState.Failed, error = error) }
            return
        }
        val s = _state.value
        _state.update { it.copy(session = SessionState.Preparing, error = null) }
        runCatching {
            audioSession.activate(AudioUseCase.FaceToFace)
            translator.prepare(
                listOf(s.userLanguage to s.peerLanguage, s.peerLanguage to s.userLanguage),
            ) {}
            speechJob?.cancel()
            speechJob = null
            speech.stop()
            val flow = speech.start(listOf(s.userLanguage, s.peerLanguage), s.peerLanguage, SpeechRecognitionStyle.LiveCaptions)
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
                idleJob?.cancel()
                idleJob = viewModelScope.launch {
                    delay(TranslationPolicy.IDLE_RESTORE_MS)
                    holdScreenAfterPause = false
                }
            } else {
                holdScreenAfterPause = false
                idleJob?.cancel()
            }
        }
        _state.update {
            it.copy(
                userPane = it.userPane.withHint(null),
                peerPane = it.peerPane.withHint(null),
            )
        }
    }

    private fun accept(utterance: Utterance) {
        if (shouldIgnoreCrosstalk(utterance)) return
        val s = _state.value
        val detected = if (SpokenText.scriptIsDecisive(utterance.text)) {
            SpokenText.inferFromScript(utterance.text, utterance.language)
        } else {
            utterance.language
        }
        val listener = routedSide.getOrPut(utterance.id) {
            LanguageRouter.targetSide(detected, s.userLanguage)
        }
        if (LanguageRouter.targetSide(detected, s.userLanguage) != listener && routedSide[utterance.id] != null) {
            val pinned = routedSide[utterance.id]
            if (pinned != null && LanguageRouter.targetSide(detected, s.userLanguage) != pinned) return
        }
        val speaker = listener.opposite
        val speaking = Copy.speaking(if (speaker == SpeakerSide.User) s.userLanguage else s.peerLanguage)
        val pane = if (listener == SpeakerSide.User) s.userPane else s.peerPane
        val existing = pane.live
        val captionId: UUID
        val carried: String
        when {
            existing != null && existing.source == detected &&
                SpokenText.isContinuation(utterance.text, existing.sourceText, detected) -> {
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
            side = listener,
            source = detected,
            sourceText = utterance.text,
            translatedText = carried,
            phase = if (utterance.isFinal) CaptionLine.Phase.Settled else CaptionLine.Phase.Streaming,
        )
        val applied = pane.applyLive(line)
        if (utterance.isFinal) {
            lastCommittedLanguage = detected
            lastCommittedAt = System.currentTimeMillis()
        }
        _state.update {
            val user = if (listener == SpeakerSide.User) applied else it.userPane.withHint(if (utterance.isFinal) null else it.userPane.speakingHint)
            val peer = if (listener == SpeakerSide.Peer) applied else it.peerPane.withHint(if (utterance.isFinal) null else it.peerPane.speakingHint)
            val hintedUser = if (speaker == SpeakerSide.User && !utterance.isFinal) user.withHint(speaking) else user.withHint(if (utterance.isFinal) null else user.speakingHint)
            val hintedPeer = if (speaker == SpeakerSide.Peer && !utterance.isFinal) peer.withHint(speaking) else peer.withHint(if (utterance.isFinal) null else peer.speakingHint)
            it.copy(userPane = hintedUser, peerPane = hintedPeer)
        }
        upsertTranscript(applied.live ?: line, speaker)
        enqueueTranslation(applied.live ?: line, utterance.isFinal)
    }

    private fun enqueueTranslation(line: CaptionLine, isFinal: Boolean) {
        val text = line.sourceText.trim()
        if (text.isEmpty()) return
        val s = _state.value
        val from = line.source
        val to = LanguageRouter.targetLanguage(line.source, s.userLanguage, s.peerLanguage)
        if (s.incrementalTranslation) {
            enqueueIncremental(line, from, to, text, isFinal)
            return
        }
        if (!isFinal) {
            // Not translated yet, but the direction is known: warm the KV for it so the
            // real call only pays for whatever the final text adds. Self-gating on the
            // pump being idle, so this never delays a translation someone is waiting for.
            viewModelScope.launch { translationPump.speculate(from, to, text) }
            return
        }
        if (sentSourceText[line.id] == text) return
        val previous = lastFinalSource[pairKey(from, to)].orEmpty().let { if (it == text) "" else it }
        val job = TranslationJob(
            utteranceId = line.id,
            from = from,
            to = to,
            text = text,
            isFinal = true,
            generation = ++translationGeneration,
            previousSource = previous,
        )
        sentSourceText[line.id] = text
        translationPump.submit(job)
    }

    /**
     * Incremental mode: translate each clause once, as soon as it stops changing, and
     * append the result. Nothing already on screen is ever revised — that is what made
     * the earlier attempt at partial translation unreadable.
     */
    private fun enqueueIncremental(
        line: CaptionLine,
        from: AppLanguage,
        to: AppLanguage,
        text: String,
        isFinal: Boolean,
    ) {
        val chunk = stablePrefix.update(line.id, text, isFinal)
        if (isFinal) sentSourceText[line.id] = text
        if (chunk.isBlank()) {
            // Everything was already translated clause by clause; the line just needs
            // settling so it stops rendering as in-progress.
            if (isFinal) settleIncremental(line.id, from, to)
            return
        }
        val job = TranslationJob(
            utteranceId = line.id,
            from = from,
            to = to,
            text = chunk,
            isFinal = isFinal,
            generation = ++translationGeneration,
            previousSource = "",
            append = true,
        )
        translationPump.submit(job)
    }

    private fun settleIncremental(id: UUID, from: AppLanguage, to: AppLanguage) {
        val joined = committedChunks[id]?.reduceOrNull { acc, chunk ->
            SpokenText.joinSegments(acc, chunk, to)
        }
        stablePrefix.forget(id)
        if (joined.isNullOrBlank()) return
        applyTranslation(id, joined, settle = true)
        committedChunks.remove(id)
        schedulePersist()
    }

    private fun fulfill(job: TranslationJob, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val id = aliases[job.utteranceId] ?: job.utteranceId
        if (job.append) {
            val chunks = committedChunks.getOrPut(id) { mutableListOf() }
            chunks += trimmed
            val joined = chunks.reduce { acc, chunk -> SpokenText.joinSegments(acc, chunk, job.to) }
            applyTranslation(id, joined, job.isFinal)
            if (job.isFinal) {
                committedChunks.remove(id)
                stablePrefix.forget(id)
                lastFinalSource[pairKey(job.from, job.to)] = committedSourceFor(job, id)
                schedulePersist()
            }
            return
        }
        val applied = lastAppliedSourceCount[id] ?: 0
        if (!job.isFinal && applied > job.text.length) return
        lastAppliedSourceCount[id] = maxOf(applied, job.text.length)
        if (job.isFinal) lastFinalSource[pairKey(job.from, job.to)] = job.text
        applyTranslation(id, trimmed, job.isFinal)
        if (job.isFinal) schedulePersist()
    }

    /** Reconstructs the source text a finished incremental line covered, for context keys. */
    private fun committedSourceFor(job: TranslationJob, id: UUID): String =
        sentSourceText[id] ?: job.text

    private fun applyTranslation(id: UUID, text: String, settle: Boolean) {
        _state.update {
            it.copy(
                userPane = it.userPane.applyTranslation(id, text, settle),
                peerPane = it.peerPane.applyTranslation(id, text, settle),
            )
        }
        transcript = transcript.map { entry ->
            if (entry.id == id) {
                entry.copy(translatedText = text, isFinal = entry.isFinal || settle)
            } else {
                entry
            }
        }
    }

    /**
     * Tokens arriving from the generator mid-flight. Pane only: the transcript and the
     * store must never see an unfinished translation, and no persist is scheduled.
     */
    private fun fulfillProgress(job: TranslationJob, text: String) {
        val id = aliases[job.utteranceId] ?: job.utteranceId
        if (job.append) {
            // The first chunk has no committed text yet, so the growing text is simply
            // what has been generated for it so far.
            val joined = committedChunks[id]?.reduceOrNull { acc, chunk ->
                SpokenText.joinSegments(acc, chunk, job.to)
            }.orEmpty()
            val growing = if (joined.isEmpty()) text else SpokenText.joinSegments(joined, text, job.to)
            _state.update {
                it.copy(
                    userPane = it.userPane.applyTranslation(id, growing, false),
                    peerPane = it.peerPane.applyTranslation(id, growing, false),
                )
            }
            return
        }
        _state.update {
            it.copy(
                userPane = it.userPane.applyTranslation(id, text, false),
                peerPane = it.peerPane.applyTranslation(id, text, false),
            )
        }
    }

    private fun upsertTranscript(line: CaptionLine, speaker: SpeakerSide) {
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
            dropPrefix(speaker, line.source)
            if (line.phase == CaptionLine.Phase.Settled) schedulePersist()
            return
        }
        val last = transcript.lastOrNull()
        if (last != null &&
            last.speaker == speaker &&
            !last.isFinal &&
            SpokenText.isContinuation(line.sourceText, last.sourceText, line.source)
        ) {
            transcript = transcript.dropLast(1) + last.copy(
                sourceText = SpokenText.preferredSource(last.sourceText, line.sourceText),
                translatedText = line.translatedText.ifEmpty { last.translatedText },
                isFinal = line.phase == CaptionLine.Phase.Settled,
            )
            dropPrefix(speaker, line.source)
            if (line.phase == CaptionLine.Phase.Settled) schedulePersist()
            return
        }
        transcript = transcript + TranscriptEntry(
            id = line.id,
            speaker = speaker,
            source = line.source,
            sourceText = line.sourceText,
            translatedText = line.translatedText,
            isFinal = line.phase == CaptionLine.Phase.Settled,
        )
        if (line.phase == CaptionLine.Phase.Settled) schedulePersist()
    }

    private fun dropPrefix(speaker: SpeakerSide, language: AppLanguage) {
        if (transcript.isEmpty()) return
        val current = transcript.last()
        var cursor = transcript.lastIndex
        while (cursor > 0) {
            val prior = transcript[cursor - 1]
            if (prior.speaker != speaker || !SpokenText.isContinuation(current.sourceText, prior.sourceText, language)) break
            transcript = transcript.toMutableList().also { it.removeAt(cursor - 1) }
            cursor -= 1
        }
    }

    private fun shouldIgnoreCrosstalk(utterance: Utterance): Boolean {
        val last = lastCommittedLanguage ?: return false
        if (System.currentTimeMillis() - lastCommittedAt >= TranslationPolicy.CROSSTALK_WINDOW_MS) return false
        if (utterance.language == last) return false
        return !UtteranceAssembler.isConfident(utterance.text, utterance.language)
    }

    private fun restore(session: TaskSession) {
        transcript = session.entries
        var user = PaneState.Empty
        var peer = PaneState.Empty
        session.entries.forEach { entry ->
            val listener = entry.speaker.opposite
            val line = CaptionLine(
                id = entry.id,
                side = listener,
                source = entry.source,
                sourceText = entry.sourceText,
                translatedText = entry.translatedText,
                phase = CaptionLine.Phase.Settled,
            )
            if (listener == SpeakerSide.User) {
                user = user.copy(history = (user.history + line).takeLast(PaneState.HISTORY_LIMIT))
            } else {
                peer = peer.copy(history = (peer.history + line).takeLast(PaneState.HISTORY_LIMIT))
            }
        }
        _state.update {
            it.copy(
                userPane = user,
                peerPane = peer,
                summary = session.summary,
                hasActiveTask = true,
                taskStartedAt = session.startedAt,
            )
        }
    }

    private fun resetCaptions() {
        transcript = emptyList()
        routedSide.clear()
        resetTranslationTracking()
        lastCommittedLanguage = null
        translationGeneration = 0
        _state.update {
            it.copy(userPane = PaneState.Empty, peerPane = PaneState.Empty, summary = null, summaryMessage = null)
        }
    }

    private fun resetTranslationTracking() {
        lastAppliedSourceCount.clear()
        sentSourceText.clear()
        lastFinalSource.clear()
        aliases.clear()
        committedChunks.clear()
        stablePrefix.reset()
    }

    private fun publishPanes() {
        _state.update { it.copy(userPane = PaneState.Empty, peerPane = PaneState.Empty) }
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

    private fun pairKey(from: AppLanguage, to: AppLanguage): String =
        "${from.translateTag}-${to.translateTag}"
}
