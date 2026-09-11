package com.duiyi.android.services.export

import android.content.Context
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.ExportSharing
import com.duiyi.android.domain.TaskSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptExportService @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExportSharing {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    override fun makeTranscriptFile(task: TaskSession, language: AppLanguage): File {
        val start = formatter.format(task.startedAt)
        val end = task.endedAt?.let { formatter.format(it) }.orEmpty()
        val direction = task.targetLanguage?.let {
            "${task.sourceLanguage.nativeName} → ${it.nativeName}"
        } ?: task.sourceLanguage.nativeName
        val body = buildString {
            appendLine("对译 · ${Copy.featureTitle(task.feature, language)}")
            appendLine("$start–$end")
            appendLine(direction)
            appendLine()
            appendLine(Copy.transcriptSection(language))
            task.entries.forEach { entry ->
                if (entry.sourceText.isNotBlank()) {
                    appendLine(entry.sourceText)
                    if (entry.translatedText.isNotBlank()) appendLine(entry.translatedText)
                    appendLine()
                }
            }
            task.summary?.let {
                appendLine(Copy.summaryTitle(language))
                appendLine(it.text)
            }
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "Duiyi-${task.startedAt.toEpochMilli()}.txt")
        file.writeText(body)
        return file
    }
}
