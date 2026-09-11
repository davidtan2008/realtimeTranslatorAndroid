package com.duiyi.android.services.persistence

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.withTransaction
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.ConversationSummary
import com.duiyi.android.domain.FeatureID
import com.duiyi.android.domain.SpeakerSide
import com.duiyi.android.domain.TaskSession
import com.duiyi.android.domain.TaskStatus
import com.duiyi.android.domain.TaskStore
import com.duiyi.android.domain.TaskTitle
import com.duiyi.android.domain.TranscriptEntry
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class DuiyiConverters {
    @TypeConverter fun uuidToString(value: UUID): String = value.toString()
    @TypeConverter fun stringToUuid(value: String): UUID = UUID.fromString(value)
    @TypeConverter fun instantToLong(value: Instant): Long = value.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long): Instant = Instant.ofEpochMilli(value)
    @TypeConverter fun featureToString(value: FeatureID): String = value.name
    @TypeConverter fun stringToFeature(value: String): FeatureID = FeatureID.valueOf(value)
    @TypeConverter fun statusToString(value: TaskStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): TaskStatus = TaskStatus.valueOf(value)
    @TypeConverter fun languageToString(value: AppLanguage?): String? = value?.name
    @TypeConverter fun stringToLanguage(value: String?): AppLanguage? = value?.let { AppLanguage.valueOf(it) }
    @TypeConverter fun speakerToString(value: SpeakerSide): String = value.name
    @TypeConverter fun stringToSpeaker(value: String): SpeakerSide = SpeakerSide.valueOf(value)
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: UUID,
    val feature: FeatureID,
    val status: TaskStatus,
    val startedAt: Instant,
    val endedAt: Instant?,
    val title: String,
    val subtitle: String?,
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage?,
    val summaryText: String?,
    val summaryOnDevice: Boolean,
)

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey val id: UUID,
    val taskId: UUID,
    val speaker: SpeakerSide,
    val source: AppLanguage,
    val sourceText: String,
    val translatedText: String,
    val isFinal: Boolean,
    val recordedAt: Instant,
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE feature = :feature AND status != 'Ended' ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(feature: FeatureID): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status != 'Ended' ORDER BY startedAt DESC")
    suspend fun unfinished(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'Ended' AND (:feature IS NULL OR feature = :feature) ORDER BY startedAt DESC")
    suspend fun ended(feature: FeatureID?): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: UUID): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: UUID)

    @Query("SELECT * FROM transcripts WHERE taskId = :taskId ORDER BY recordedAt ASC")
    suspend fun entries(taskId: UUID): List<TranscriptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<TranscriptEntity>)

    @Query("DELETE FROM transcripts WHERE taskId = :taskId")
    suspend fun deleteEntries(taskId: UUID)
}

@Database(entities = [TaskEntity::class, TranscriptEntity::class], version = 1, exportSchema = false)
@TypeConverters(DuiyiConverters::class)
abstract class DuiyiDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
}

@Singleton
class RoomTaskStore @Inject constructor(
    private val db: DuiyiDatabase,
) : TaskStore {
    private val dao get() = db.tasks()

    override suspend fun activeTask(forFeature: FeatureID): TaskSession? =
        dao.active(forFeature)?.toSession()

    override suspend fun unfinishedTasks(): List<TaskSession> =
        dao.unfinished().map { it.toSession() }

    override suspend fun startNew(feature: FeatureID, source: AppLanguage, target: AppLanguage?): TaskSession {
        dao.active(feature)?.let { old ->
            val entries = dao.entries(old.id)
            if (entries.none { it.sourceText.isNotBlank() }) {
                dao.deleteEntries(old.id)
                dao.deleteTask(old.id)
            } else {
                dao.upsert(old.copy(status = TaskStatus.Ended, endedAt = Instant.now()))
            }
        }
        val session = TaskEntity(
            id = UUID.randomUUID(),
            feature = feature,
            status = TaskStatus.Running,
            startedAt = Instant.now(),
            endedAt = null,
            title = TaskTitle.make(feature, source, target, source),
            subtitle = null,
            sourceLanguage = source,
            targetLanguage = target,
            summaryText = null,
            summaryOnDevice = false,
        )
        dao.upsert(session)
        return session.toSession(emptyList())
    }

    override suspend fun append(taskId: UUID, entries: List<TranscriptEntry>) {
        db.withTransaction {
            dao.deleteEntries(taskId)
            if (entries.isNotEmpty()) {
                dao.upsertEntries(entries.map { it.toEntity(taskId) })
            }
            val task = dao.task(taskId) ?: return@withTransaction
            val subtitle = TaskTitle.subtitle(entries)
            dao.upsert(task.copy(subtitle = subtitle ?: task.subtitle))
        }
    }

    override suspend fun setStatus(taskId: UUID, status: TaskStatus) {
        val task = dao.task(taskId) ?: return
        dao.upsert(task.copy(status = status))
    }

    override suspend fun saveSummary(taskId: UUID, summary: ConversationSummary) {
        val task = dao.task(taskId) ?: return
        dao.upsert(task.copy(summaryText = summary.text, summaryOnDevice = summary.generatedOnDevice))
    }

    override suspend fun end(taskId: UUID, summary: ConversationSummary?): TaskSession = db.withTransaction {
        val task = dao.task(taskId) ?: error("not found")
        val entries = dao.entries(taskId)
        if (entries.none { it.sourceText.isNotBlank() }) {
            dao.deleteEntries(taskId)
            dao.deleteTask(taskId)
            return@withTransaction task.toSession(entries).copy(status = TaskStatus.Ended, endedAt = Instant.now())
        }
        val ended = task.copy(
            status = TaskStatus.Ended,
            endedAt = Instant.now(),
            summaryText = summary?.text ?: task.summaryText,
            summaryOnDevice = summary?.generatedOnDevice ?: task.summaryOnDevice,
        )
        dao.upsert(ended)
        ended.toSession(entries)
    }

    override suspend fun list(filter: FeatureID?): List<TaskSession> =
        dao.ended(filter).map { it.toSession() }

    override suspend fun task(id: UUID): TaskSession? = dao.task(id)?.toSession()

    override suspend fun delete(id: UUID) {
        dao.deleteEntries(id)
        dao.deleteTask(id)
    }

    private suspend fun TaskEntity.toSession(): TaskSession = toSession(dao.entries(id))

    private fun TaskEntity.toSession(entries: List<TranscriptEntity>) = TaskSession(
        id = id,
        feature = feature,
        status = status,
        startedAt = startedAt,
        endedAt = endedAt,
        title = title,
        subtitle = subtitle,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        entries = entries.map { it.toDomain() },
        summary = summaryText?.let { ConversationSummary(it, summaryOnDevice) },
    )

    private fun TranscriptEntry.toEntity(taskId: UUID) = TranscriptEntity(
        id = id,
        taskId = taskId,
        speaker = speaker,
        source = source,
        sourceText = sourceText,
        translatedText = translatedText,
        isFinal = isFinal,
        recordedAt = recordedAt,
    )

    private fun TranscriptEntity.toDomain() = TranscriptEntry(
        id = id,
        speaker = speaker,
        source = source,
        sourceText = sourceText,
        translatedText = translatedText,
        isFinal = isFinal,
        recordedAt = recordedAt,
    )
}
