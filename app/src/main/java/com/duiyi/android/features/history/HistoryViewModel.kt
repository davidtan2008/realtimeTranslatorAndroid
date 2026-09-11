package com.duiyi.android.features.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duiyi.android.app.PreferencesStore
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.ConversationSummarizing
import com.duiyi.android.domain.ConversationSummary
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.DuiyiError
import com.duiyi.android.domain.ExportSharing
import com.duiyi.android.domain.FeatureID
import com.duiyi.android.domain.SummaryAvailability
import com.duiyi.android.domain.TaskSession
import com.duiyi.android.domain.TaskStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val language: AppLanguage = AppLanguage.ZhHans,
    val tasks: List<TaskSession> = emptyList(),
    val filter: FeatureID? = null,
    val pendingDelete: UUID? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val store: TaskStore,
    preferences: PreferencesStore,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState(language = preferences.userLanguage))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun setFilter(filter: FeatureID?) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    fun requestDelete(id: UUID) = _state.update { it.copy(pendingDelete = id) }

    fun confirmDelete(yes: Boolean) {
        val id = _state.value.pendingDelete
        _state.update { it.copy(pendingDelete = null) }
        if (yes && id != null) {
            viewModelScope.launch {
                store.delete(id)
                load()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.update { it.copy(tasks = store.list(it.filter)) }
    }
}

data class TaskDetailUiState(
    val language: AppLanguage = AppLanguage.ZhHans,
    val task: TaskSession? = null,
    val summaryOpen: Boolean = false,
    val summarizing: Boolean = false,
    val summary: ConversationSummary? = null,
    val summaryAvailability: SummaryAvailability = SummaryAvailability.Unavailable,
    val summaryMessage: String? = null,
    val exportFile: File? = null,
)

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: TaskStore,
    private val summarizer: ConversationSummarizing,
    private val export: ExportSharing,
    private val preferences: PreferencesStore,
) : ViewModel() {
    private val taskId: UUID = UUID.fromString(checkNotNull(savedStateHandle["taskId"]))
    private val _state = MutableStateFlow(TaskDetailUiState(language = preferences.userLanguage))
    val state: StateFlow<TaskDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val task = store.task(taskId)
            _state.update { it.copy(task = task, summary = task?.summary) }
        }
    }

    fun requestSummary() {
        viewModelScope.launch {
            val task = _state.value.task ?: return@launch
            val language = _state.value.language
            val availability = summarizer.availability()
            val message = if (task.entries.none { it.sourceText.isNotBlank() }) {
                DuiyiError.SummaryEmpty.userMessage(language)
            } else {
                Copy.summaryAvailabilityMessage(availability, language)
            }
            _state.update {
                it.copy(
                    summaryOpen = true,
                    summarizing = false,
                    summaryAvailability = availability,
                    summaryMessage = message,
                )
            }
        }
    }

    fun dismissSummary() = _state.update { it.copy(summaryOpen = false) }

    fun prepareExport(): File? {
        val task = _state.value.task ?: return null
        val file = export.makeTranscriptFile(task, _state.value.language)
        _state.update { it.copy(exportFile = file) }
        return file
    }
}
