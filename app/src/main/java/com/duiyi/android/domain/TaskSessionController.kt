package com.duiyi.android.domain

import java.util.UUID

class TaskSessionController(
    private val feature: FeatureID,
    private val store: TaskStore,
) {
    var current: TaskSession? = null
        private set

    val hasActiveTask: Boolean
        get() = current?.let { it.status != TaskStatus.Ended } == true

    val taskId: UUID? get() = current?.id
    val startedAt get() = current?.startedAt

    suspend fun restore(): TaskSession? {
        current = store.activeTask(feature)
        return current
    }

    suspend fun beginNew(source: AppLanguage, target: AppLanguage?): TaskSession? {
        val session = store.startNew(feature, source, target)
        current = session
        return session
    }

    suspend fun persist(entries: List<TranscriptEntry>) {
        val id = current?.id ?: return
        store.append(id, entries)
    }

    suspend fun setListening(listening: Boolean) {
        val id = current?.id ?: return
        val status = if (listening) TaskStatus.Running else TaskStatus.PausedListening
        store.setStatus(id, status)
        current = current?.copy(status = status)
    }

    suspend fun saveSummary(summary: ConversationSummary) {
        val id = current?.id ?: return
        store.saveSummary(id, summary)
        current = current?.copy(summary = summary)
    }

    suspend fun finish(entries: List<TranscriptEntry>, summary: ConversationSummary?): TaskSession? {
        val id = current?.id ?: return null
        store.append(id, entries)
        val usable = entries.any { it.sourceText.isNotBlank() }
        if (!usable) {
            store.delete(id)
            current = null
            return null
        }
        val saved = store.end(id, summary)
        current = null
        return saved
    }
}
