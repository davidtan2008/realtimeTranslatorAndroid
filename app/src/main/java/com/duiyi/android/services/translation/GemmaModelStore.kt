package com.duiyi.android.services.translation

import android.content.Context
import com.duiyi.android.domain.DuiyiError
import com.duiyi.android.services.DuiyiLog
import com.duiyi.android.services.assets.RemoteFileDownloader
import com.duiyi.android.services.speech.SpeechEngineException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GemmaDownloadProgress(
    val running: Boolean = false,
    val fraction: Float = 0f,
    val failed: Boolean = false,
)

data class GemmaSnapshot(
    val ready: Boolean,
    val bytes: Long,
    val expectedBytes: Long,
)

@Singleton
class GemmaModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Mutex()
    private val _progress = MutableStateFlow(GemmaDownloadProgress())
    val progress: StateFlow<GemmaDownloadProgress> = _progress.asStateFlow()

    fun isReady(): Boolean = GemmaModelSpec.isComplete(file().length())

    fun snapshot(): GemmaSnapshot {
        val bytes = bytesOnDisk()
        return GemmaSnapshot(
            ready = isReady(),
            bytes = bytes,
            expectedBytes = GemmaModelSpec.EXPECTED_BYTES,
        )
    }

    fun modelPath(): String = file().absolutePath

    suspend fun ensureReady(onProgress: (Float) -> Unit = {}) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (isReady()) {
                onProgress(1f)
                _progress.value = GemmaDownloadProgress(fraction = 1f)
                return@withContext
            }
            _progress.value = GemmaDownloadProgress(running = true, fraction = 0f)
            try {
                val dest = file()
                dest.parentFile?.mkdirs()
                RemoteFileDownloader.download(
                    urls = GemmaModelSpec.mirrors,
                    dest = dest,
                    minBytes = GemmaModelSpec.MIN_BYTES,
                    userAgent = "Duiyi/0.1 (Android; llama.cpp)",
                ) { copied ->
                    val overall = (copied.toFloat() / GemmaModelSpec.EXPECTED_BYTES.toFloat()).coerceIn(0f, 0.99f)
                    _progress.value = GemmaDownloadProgress(running = true, fraction = overall)
                    onProgress(overall)
                }
                if (!isReady()) throw SpeechEngineException(DuiyiError.ModelsMissing)
                DuiyiLog.i("gemma model ready bytes=${dest.length()}")
                onProgress(1f)
                _progress.value = GemmaDownloadProgress(fraction = 1f)
            } catch (error: Exception) {
                _progress.value = GemmaDownloadProgress(
                    running = false,
                    fraction = _progress.value.fraction,
                    failed = true,
                )
                if (error is SpeechEngineException) throw error
                DuiyiLog.w("gemma model download failed ${error.message}")
                throw SpeechEngineException(DuiyiError.ModelsMissing)
            }
        }
    }

    suspend fun deleteAll() = lock.withLock {
        withContext(Dispatchers.IO) {
            File(context.filesDir, GemmaModelSpec.DIR).deleteRecursively()
            _progress.value = GemmaDownloadProgress()
        }
    }

    private fun file(): File = File(File(context.filesDir, GemmaModelSpec.DIR), GemmaModelSpec.FILE_NAME)

    private fun bytesOnDisk(): Long {
        val dest = file()
        if (dest.length() > 0L) return dest.length()
        return File(dest.parentFile, "${dest.name}.part").length()
    }
}
