package com.raulsc.lenguareaccion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class WhisperModel(
    val label: String,
    val description: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
) {
    TINY_Q5_1(
        label = "Ultrarrápido",
        description = "Whisper tiny · 32 MB · para pruebas y vídeos sencillos",
        fileName = "ggml-tiny-q5_1.bin",
        bytes = 32_152_673L,
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
    ),
    BASE_Q5_1(
        label = "Equilibrado",
        description = "Whisper base · 60 MB · recomendado en la tablet",
        fileName = "ggml-base-q5_1.bin",
        bytes = 59_707_625L,
        sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
    ),
    SMALL_Q5_1(
        label = "Calidad alta",
        description = "Whisper small · 190 MB · lento en esta tablet",
        fileName = "ggml-small-q5_1.bin",
        bytes = 190_085_487L,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
    ),
    LARGE_V3_TURBO_Q5_0(
        label = "Experimental",
        description = "Whisper large-v3-turbo · 574 MB · muy lento sin GPU",
        fileName = "ggml-large-v3-turbo-q5_0.bin",
        bytes = 574_041_195L,
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
    );

    val downloadUrl: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

class WhisperModelStore(private val context: Context) {
    private val modelDirectory = File(context.noBackupFilesDir, "whisper-models").apply { mkdirs() }

    fun file(model: WhisperModel): File = File(modelDirectory, model.fileName)

    fun isInstalled(model: WhisperModel): Boolean =
        file(model).let { it.isFile && it.length() == model.bytes }

    suspend fun download(model: WhisperModel, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val target = file(model)
        if (isInstalled(model) && sha256(target).equals(model.sha256, ignoreCase = true)) {
            progress(100)
            return@withContext target
        }

        val partial = File(modelDirectory, "${model.fileName}.partial")
        if (partial.length() > model.bytes) partial.delete()
        try {
            var copied = partial.length()
            var connection = open(model.downloadUrl, copied)
            if (copied > 0L && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect()
                partial.delete()
                copied = 0L
                connection = open(model.downloadUrl, 0L)
            }
            connection.inputStream.buffered().use { input ->
                java.io.FileOutputStream(partial, copied > 0L).buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var lastProgress = ((copied * 100L) / model.bytes).toInt()
                    progress(lastProgress)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val percent = ((copied * 100L) / model.bytes).toInt().coerceIn(0, 99)
                        if (percent != lastProgress) {
                            lastProgress = percent
                            progress(percent)
                        }
                    }
                }
            }
            check(partial.length() == model.bytes) {
                partial.delete()
                "Tamaño inesperado: ${partial.length()} de ${model.bytes} bytes"
            }
            check(sha256(partial).equals(model.sha256, ignoreCase = true)) {
                partial.delete()
                "La firma SHA-256 del modelo no coincide"
            }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "No se pudo activar el modelo descargado" }
            progress(100)
            target
        } catch (error: Exception) {
            throw error
        }
    }

    fun delete(model: WhisperModel) {
        file(model).delete()
        File(modelDirectory, "${model.fileName}.partial").delete()
    }

    private fun open(url: String, offset: Long): HttpURLConnection {
        var current = url
        repeat(8) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 90_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "LenguaReaccion/${BuildConfig.VERSION_NAME}")
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
            val status = connection.responseCode
            if (status in 300..399) {
                current = connection.getHeaderField("Location") ?: error("Redirección sin destino")
                connection.disconnect()
            } else {
                check(status in 200..299) { "Respuesta HTTP $status al descargar el modelo" }
                return connection
            }
        }
        error("Demasiadas redirecciones al descargar el modelo")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
