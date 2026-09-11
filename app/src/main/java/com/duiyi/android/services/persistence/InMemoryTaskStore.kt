package com.duiyi.android.services.persistence

import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.ConversationSummary
import com.duiyi.android.domain.FeatureID
import com.duiyi.android.domain.TaskSession
import com.duiyi.android.domain.TaskStatus
import com.duiyi.android.domain.TaskStore
import com.duiyi.android.domain.TaskTitle
import com.duiyi.android.domain.TranscriptEntry
import java.time.Instant
import java.util.UUID

class InMemoryTaskStore : TaskStore {
    private val tasks = LinkedHashMap<UUID, TaskSession>()

    override suspend fun activeTask(forFeature: FeatureID): TaskSession? =
        tasks.values.lastOrNull { it.feature == forFeature && it.status != TaskStatus.Ended }

    override suspend fun unfinishedTasks(): List<TaskSession> =
        tasks.values.filter { it.status != TaskStatus.Ended }.reversed()

    override suspend fun startNew(feature: FeatureID, source: AppLanguage, target: AppLanguage?): TaskSession {
        activeTask(feature)?.let { old ->
            if (!old.hasTranscript) tasks.remove(old.id) else {
                tasks[old.id] = old.copy(status = TaskStatus.Ended, endedAt = Instant.now())
            }
        }
        val session = TaskSession(
            id = UUID.randomUUID(),
            feature = feature,
            status = TaskStatus.Running,
            startedAt = Instant.now(),
            endedAt = null,
            title = TaskTitle.make(feature, source, target, source),
            sourceLanguage = source,
            targetLanguage = target,
        )
        tasks[session.id] = session
        return session
    }

    override suspend fun append(taskId: UUID, entries: List<TranscriptEntry>) {
        val current = tasks[taskId] ?: return
        val merged = LinkedHashMap<UUID, TranscriptEntry>()
        current.entries.forEach { merged[it.id] = it }
        entries.forEach { merged[it.id] = it }
        tasks[taskId] = current.copy(
            entries = merged.values.toList(),
            subtitle = TaskTitle.subtitle(merged.values.toList()) ?: current.subtitle,
        )
    }

    override suspend fun setStatus(taskId: UUID, status: TaskStatus) {
        val current = tasks[taskId] ?: return
        tasks[taskId] = current.copy(status = status)
    }

    override suspend fun saveSummary(taskId: UUID, summary: ConversationSummary) {
        val current = tasks[taskId] ?: return
        tasks[taskId] = current.copy(summary = summary)
    }

    override suspend fun end(taskId: UUID, summary: ConversationSummary?): TaskSession {
        val current = tasks[taskId] ?: error("not found")
        if (!current.hasTranscript) {
            tasks.remove(taskId)
            return current.copy(status = TaskStatus.Ended, endedAt = Instant.now())
        }
        val ended = current.copy(
            status = TaskStatus.Ended,
            endedAt = Instant.now(),
            summary = summary ?: current.summary,
        )
        tasks[taskId] = ended
        return ended
    }

    override suspend fun list(filter: FeatureID?): List<TaskSession> =
        tasks.values
            .filter { it.status == TaskStatus.Ended }
            .filter { filter == null || it.feature == filter }
            .reversed()

    override suspend fun task(id: UUID): TaskSession? = tasks[id]

    override suspend fun delete(id: UUID) {
        tasks.remove(id)
    }
}
