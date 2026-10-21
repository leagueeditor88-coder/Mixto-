package com.example.automix

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * AMX-AI-LOCAL-001
 *
 * Descarga y conserva el archivo GGUF en almacenamiento privado de Automix.
 * Esta clase NO ejecuta todavía el modelo: el motor nativo llama.cpp/JNI se
 * integrará en una fase posterior, después de validar descarga y espacio.
 */
class LocalAiModelManager(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        const val MODEL_FILE_NAME = "SmolLM2-135M-Instruct-Q4_K_M.gguf"
        const val MODEL_BYTES_APPROX = 105_454_144L
        const val MODEL_URL =
            "https://huggingface.co/lukejoe/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
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
        val complete = file.exists() && file.length() > 0L
        return ModelStatus(
            exists = file.exists() || partial.exists(),
            bytesDownloaded = if (complete) file.length() else partial.length(),
            expectedBytes = MODEL_BYTES_APPROX,
            isComplete = complete,
            path = if (complete) file.absolutePath else null
        )
    }

    /**
     * Descarga con soporte de reanudación HTTP Range cuando el servidor lo permite.
     * Devuelve el estado final. Ejecutar desde coroutine; nunca desde el hilo UI.
     */
    suspend fun download(
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): ModelStatus = withContext(Dispatchers.IO) {
        if (finalFile.exists() && finalFile.length() > 0L) return@withContext status()

        var offset = if (partialFile.exists()) partialFile.length() else 0L
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Automix-Android/1.0")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }

        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("El servidor respondió HTTP $code")
            }

            // Si el servidor ignoró Range, reiniciar para no concatenar dos copias.
            if (offset > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
                offset = 0L
                partialFile.delete()
            }

            val totalFromHeader = connection.getHeaderField("Content-Length")
                ?.toLongOrNull()?.let { it + offset } ?: MODEL_BYTES_APPROX

            connection.inputStream.use { input ->
                RandomAccessFile(partialFile, "rw").use { output ->
                    output.seek(offset)
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = offset
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, totalFromHeader)
                    }
                    output.fd.sync()
                }
            }

            if (partialFile.length() < 50_000_000L) {
                throw IllegalStateException(
                    "El archivo descargado es demasiado pequeño (${partialFile.length()} bytes). " +
                        "Puede que el enlace haya devuelto una página de error."
                )
            }

            // Validación básica GGUF: magic bytes "GGUF".
            RandomAccessFile(partialFile, "r").use { file ->
                val magic = ByteArray(4)
                file.readFully(magic)
                if (!magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) {
                    throw IllegalStateException("El archivo descargado no parece ser un modelo GGUF válido.")
                }
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partialFile.renameTo(finalFile)) {
                partialFile.copyTo(finalFile, overwrite = true)
                partialFile.delete()
            }
            status()
        } finally {
            connection.disconnect()
        }
    }

    fun deleteModel(): Boolean {
        val partialDeleted = !partialFile.exists() || partialFile.delete()
        val modelDeleted = !finalFile.exists() || finalFile.delete()
        return partialDeleted && modelDeleted
    }
}
