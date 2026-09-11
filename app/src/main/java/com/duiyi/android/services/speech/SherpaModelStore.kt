package com.duiyi.android.services.speech

import android.content.Context
import android.os.Build
import com.duiyi.android.domain.DuiyiError
import com.duiyi.android.services.DuiyiLog
import com.duiyi.android.services.assets.RemoteFileDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SherpaDownloadProgress(
    val running: Boolean = false,
    val fraction: Float = 0f,
    val failed: Boolean = false,
)

data class SherpaFileSnapshot(
    val fileName: String,
    val bytes: Long,
    val expectedBytes: Long,
    val ready: Boolean,
)

data class SherpaSnapshot(
    val required: Boolean,
    val ready: Boolean,
    val visible: Boolean,
    val bytes: Long,
    val expectedBytes: Long,
    val files: List<SherpaFileSnapshot>,
)

@Singleton
class SherpaModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Mutex()
    private val _progress = MutableStateFlow(SherpaDownloadProgress())
    val progress: StateFlow<SherpaDownloadProgress> = _progress.asStateFlow()

    fun isRequired(): Boolean = SpeechEnginePolicy.useSherpaOnDevice(
        SpeechEnginePolicy.isHonorFamily(Build.MANUFACTURER, Build.BRAND),
    )

    fun isReady(): Boolean = SherpaModelSpec.parts.all { part ->
        SherpaModelSpec.isComplete(part.fileName, fileFor(part.fileName).length())
    }

    fun snapshot(): SherpaSnapshot {
        pruneLegacy()
        val files = SherpaModelSpec.parts.map { part ->
            val bytes = bytesOnDisk(part.fileName)
            SherpaFileSnapshot(
                fileName = part.fileName,
                bytes = bytes,
                expectedBytes = part.expectedBytes,
                ready = SherpaModelSpec.isComplete(part.fileName, fileFor(part.fileName).length()),
            )
        }
        return SherpaSnapshot(
            required = isRequired(),
            ready = files.all { it.ready },
            visible = isRequired() || files.any { it.bytes > 0L },
            bytes = files.sumOf { it.bytes },
            expectedBytes = SherpaModelSpec.expectedTotalBytes,
            files = files,
        )
    }

    fun modelPath(): String = fileFor("model.int8.onnx").absolutePath
    fun tokensPath(): String = fileFor("tokens.txt").absolutePath
    fun vadPath(): String = fileFor("silero_vad.onnx").absolutePath

    suspend fun ensureReady(onProgress: (Float) -> Unit = {}) = lock.withLock {
        withContext(Dispatchers.IO) {
            pruneLegacy()
            if (isReady()) {
                onProgress(1f)
                _progress.value = SherpaDownloadProgress(fraction = 1f)
                return@withContext
            }
            _progress.value = SherpaDownloadProgress(running = true, fraction = 0f)
            try {
                val root = File(context.filesDir, SherpaModelSpec.DIR)
                if (!root.exists() && !root.mkdirs()) {
                    throw SpeechEngineException(DuiyiError.SpeechModelsMissing)
                }
                val pending = SherpaModelSpec.parts.filter { part ->
                    !SherpaModelSpec.isComplete(part.fileName, fileFor(part.fileName).length())
                }
                val weights = pending.sumOf { it.expectedBytes }.coerceAtLeast(1)
                var finished = 0L
                pending.forEach { part ->
                    coroutineContext.ensureActive()
                    downloadPart(part) { read ->
                        val overall = ((finished + read).toFloat() / weights.toFloat()).coerceIn(0f, 0.99f)
                        _progress.value = SherpaDownloadProgress(running = true, fraction = overall)
                        onProgress(overall)
                    }
                    finished += part.expectedBytes
                }
                if (!isReady()) throw SpeechEngineException(DuiyiError.SpeechModelsMissing)
                onProgress(1f)
                _progress.value = SherpaDownloadProgress(fraction = 1f)
            } catch (error: Exception) {
                _progress.value = SherpaDownloadProgress(
                    running = false,
                    fraction = _progress.value.fraction,
                    failed = true,
                )
                throw error
            }
        }
    }

    suspend fun deleteAll() = lock.withLock {
        withContext(Dispatchers.IO) {
            File(context.filesDir, SherpaModelSpec.DIR).deleteRecursively()
            pruneLegacy()
            _progress.value = SherpaDownloadProgress()
        }
    }

    private fun pruneLegacy() {
        File(context.filesDir, SherpaModelSpec.LEGACY_DIR).deleteRecursively()
    }

    private fun fileFor(fileName: String): File =
        File(File(context.filesDir, SherpaModelSpec.DIR), fileName)

    private fun bytesOnDisk(fileName: String): Long {
        val dest = fileFor(fileName)
        if (dest.length() > 0L) return dest.length()
        return File(dest.parentFile, "$fileName.part").length()
    }

    private suspend fun downloadPart(part: SherpaModelSpec.Part, onBytes: (Long) -> Unit) {
        val dest = fileFor(part.fileName)
        try {
            RemoteFileDownloader.download(
                urls = part.mirrors,
                dest = dest,
                minBytes = part.minBytes,
                userAgent = "Duiyi/0.1 (Android; sherpa-onnx)",
                onBytes = onBytes,
            )
            DuiyiLog.i("sherpa model ready ${part.fileName} bytes=${dest.length()}")
            SpeechProbe.i(context, "sherpa model ${part.fileName} bytes=${dest.length()}")
        } catch (error: Exception) {
            DuiyiLog.w("sherpa model failed ${part.fileName} ${error.message}")
            SpeechProbe.i(context, "sherpa model failed ${part.fileName}")
            throw SpeechEngineException(DuiyiError.SpeechModelsMissing)
        }
    }
}
