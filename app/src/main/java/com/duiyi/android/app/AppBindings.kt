package com.duiyi.android.app

import android.content.Context
import androidx.room.Room
import com.duiyi.android.domain.AudioSessionConfiguring
import com.duiyi.android.domain.ConversationSummarizing
import com.duiyi.android.domain.DeviceCapabilityProbing
import com.duiyi.android.domain.ExportSharing
import com.duiyi.android.domain.LanguageCataloging
import com.duiyi.android.domain.PermissionGranting
import com.duiyi.android.domain.SpeechRecognizing
import com.duiyi.android.domain.SpeechSynthesizing
import com.duiyi.android.domain.TaskStore
import com.duiyi.android.domain.Translating
import com.duiyi.android.services.audio.AudioSessionPolicy
import com.duiyi.android.services.export.TranscriptExportService
import com.duiyi.android.services.permissions.PermissionService
import com.duiyi.android.services.persistence.DuiyiDatabase
import com.duiyi.android.services.persistence.RoomTaskStore
import com.duiyi.android.services.speech.AndroidSpeechEngine
import com.duiyi.android.services.speech.DeviceCapabilityProbe
import com.duiyi.android.services.speech.DeviceLanguageCatalog
import com.duiyi.android.services.summary.HonestSummarizer
import com.duiyi.android.services.translation.GemmaTranslator
import com.duiyi.android.services.tts.SystemSpeechSynthesizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): DuiyiDatabase =
        Room.databaseBuilder(context, DuiyiDatabase::class.java, "duiyi.db").build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {
    @Binds @Singleton abstract fun taskStore(impl: RoomTaskStore): TaskStore
    @Binds @Singleton abstract fun catalog(impl: DeviceLanguageCatalog): LanguageCataloging
    @Binds @Singleton abstract fun speech(impl: AndroidSpeechEngine): SpeechRecognizing
    @Binds @Singleton abstract fun translator(impl: GemmaTranslator): Translating
    @Binds @Singleton abstract fun tts(impl: SystemSpeechSynthesizer): SpeechSynthesizing
    @Binds @Singleton abstract fun audio(impl: AudioSessionPolicy): AudioSessionConfiguring
    @Binds @Singleton abstract fun permissions(impl: PermissionService): PermissionGranting
    @Binds @Singleton abstract fun probe(impl: DeviceCapabilityProbe): DeviceCapabilityProbing
    @Binds @Singleton abstract fun export(impl: TranscriptExportService): ExportSharing
    @Binds @Singleton abstract fun summary(impl: HonestSummarizer): ConversationSummarizing
}
