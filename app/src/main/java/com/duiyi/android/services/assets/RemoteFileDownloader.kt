package com.duiyi.android.services.assets

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

object RemoteFileDownloader {
    suspend fun download(
        urls: List<String>,
        dest: File,
        minBytes: Long,
        userAgent: String,
        onBytes: (Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val errors = mutableListOf<String>()
        for (url in urls) {
            val tmp = File(dest.parentFile, "${dest.name}.part")
            try {
                downloadUrl(url, tmp, minBytes, userAgent, onBytes)
                if (tmp.length() >= minBytes) {
                    if (dest.exists()) dest.delete()
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    return
                }
                errors += "$url size=${tmp.length()}"
                tmp.delete()
            } catch (error: Exception) {
                errors += "$url ${error.message}"
            }
        }
        throw IllegalStateException(errors.joinToString(" | ").ifBlank { "download failed" })
    }

    private suspend fun downloadUrl(
        url: String,
        dest: File,
        minBytes: Long,
        userAgent: String,
        onBytes: (Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val already = dest.takeIf { it.exists() }?.length() ?: 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
            if (already > 0L) {
                setRequestProperty("Range", "bytes=$already-")
            }
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("http $code")
            }
            val append = code == 206 && already > 0L
            if (!append && dest.exists()) {
                dest.delete()
            }
            val out = if (append) {
                RandomAccessFile(dest, "rw").apply { seek(dest.length()) }
            } else {
                null
            }
            try {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = if (append) dest.length() else 0L
                    onBytes(copied)
                    if (out != null) {
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            coroutineContext.ensureActive()
                            onBytes(copied)
                        }
                    } else {
                        dest.outputStream().use { output ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                coroutineContext.ensureActive()
                                onBytes(copied)
                            }
                        }
                    }
                }
            } finally {
                out?.close()
            }
            if (dest.length() < minBytes) {
                dest.delete()
                throw IllegalStateException("short ${dest.length()}")
            }
        } finally {
            connection.disconnect()
        }
    }
}
