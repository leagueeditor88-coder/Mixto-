package com.example.automix

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Automix local model downloader.
 * Model selected: Qwen2.5-0.5B-Instruct, Q2_K GGUF.
 * This class downloads and validates the GGUF file; it does not run inference by itself.
 */
class LocalAiModelManager(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        const val MODEL_FILE_NAME = "Qwen2.5-0.5B-Instruct-Q2_K.gguf"
        // Approximate only: different mirrors may provide slightly different Q2_K file sizes.
        const val MODEL_BYTES_APPROX = 370_000_000L
        private const val MIN_VALID_MODEL_BYTES = 100_000_000L
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000

        /**
         * Two real model-file locations, tried in order. Filenames are case-sensitive.
         * Source 1 is the smaller TensorBlock conversion (~339 MB); source 2 is the
         * original Qwen repository mirror (~415 MB).
         */
        private val MODEL_URLS = listOf(
            "https://huggingface.co/tensorblock/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q2_K.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q2_k.gguf"
        )
    }

    data class ModelStatus(
        val exists: Boolean,
        val bytesDownloaded: Long,
        val expectedBytes: Long,
        val isComplete: Boolean,
        val path: String?
    ) {
        val progressPercent: Int
            get() = if (expectedBytes <= 0L) 0
            else ((bytesDownloaded * 100L) / expectedBytes).coerceIn(0L, 100L).toInt()
    }

    private val modelDir: File
        get() = File(appContext.filesDir, "local_ai").apply { mkdirs() }

    private val finalFile: File
        get() = File(modelDir, MODEL_FILE_NAME)

    private val partialFile: File
        get() = File(modelDir, "$MODEL_FILE_NAME.part")

    fun status(): ModelStatus {
        val file = finalFile
        val partial = partialFile
        val complete = file.exists() && file.length() >= MIN_VALID_MODEL_BYTES
        val bytes = if (complete) file.length() else partial.length()
        val expected = if (complete) file.length() else MODEL_BYTES_APPROX
        return ModelStatus(
            exists = complete || partial.exists(),
            bytesDownloaded = bytes,
            expectedBytes = expected,
            isComplete = complete,
            path = if (complete) file.absolutePath else null
        )
    }

    /** Download from both mirrors if necessary, resume a partial first attempt, and validate GGUF. */
    suspend fun download(
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): ModelStatus = withContext(Dispatchers.IO) {
        if (finalFile.exists() && isValidGguf(finalFile)) return@withContext status()
        if (finalFile.exists()) finalFile.delete()

        val errors = mutableListOf<String>()
        for ((index, modelUrl) in MODEL_URLS.withIndex()) {
            // A partial file is only resumed against the first source. If it failed,
            // discard it before switching mirrors rather than joining unrelated streams.
            if (index > 0) partialFile.delete()
            var offset = if (index == 0 && partialFile.exists()) partialFile.length() else 0L
            var connection: HttpURLConnection? = null
            try {
                val activeConnection = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Automix-Android/1.0")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
                }
                connection = activeConnection

                activeConnection.connect()
                val code = activeConnection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code desde ${URL(modelUrl).host}")
                }

                // If the server ignored Range, start again instead of appending a second copy.
                if (offset > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
                    partialFile.delete()
                    offset = 0L
                }

                val rangeTotal = activeConnection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')?.toLongOrNull()
                val contentLength = activeConnection.getHeaderField("Content-Length")?.toLongOrNull()
                val total = rangeTotal ?: contentLength?.let { it + offset } ?: MODEL_BYTES_APPROX

                activeConnection.inputStream.use { input ->
                    RandomAccessFile(partialFile, "rw").use { output ->
                        output.seek(offset)
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = offset
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            onProgress(downloaded, total)
                        }
                        output.fd.sync()
                    }
                }

                if (!isValidGguf(partialFile)) {
                    throw IllegalStateException(
                        "El archivo recibido no parece un GGUF válido (tamaño: ${partialFile.length()} bytes)."
                    )
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partialFile.renameTo(finalFile)) {
                    partialFile.copyTo(finalFile, overwrite = true)
                    partialFile.delete()
                }
                return@withContext status()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                errors += "${URL(modelUrl).host}: ${e.message ?: e.javaClass.simpleName}"
                partialFile.delete()
            } finally {
                connection?.disconnect()
            }
        }

        throw IllegalStateException(
            "Fallaron las 2 fuentes de Qwen. ${errors.joinToString(" | ")}"
        )
    }

    private fun isValidGguf(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_MODEL_BYTES) return false
        return try {
            RandomAccessFile(file, "r").use { input ->
                val magic = ByteArray(4)
                input.readFully(magic)
                magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46)) // GGUF
            }
        } catch (_: Exception) {
            false
        }
    }

    fun deleteModel(): Boolean {
        val partialDeleted = !partialFile.exists() || partialFile.delete()
        val modelDeleted = !finalFile.exists() || finalFile.delete()
        return partialDeleted && modelDeleted
    }
}
