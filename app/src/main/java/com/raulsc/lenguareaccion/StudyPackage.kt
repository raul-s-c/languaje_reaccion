package com.raulsc.lenguareaccion

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ImportedStudyPackage(val videoId: String, val videoFilename: String,
    val durationMillis: Long, val segments: List<SubtitleSegment>)

/** Bounded parsing without extraction, so archive paths never become filesystem paths. */
object StudyPackage {
    fun read(context: Context, uri: Uri): List<SubtitleSegment> {
        return readPackage(context, uri).segments
    }

    fun readPackage(context: Context, uri: Uri): ImportedStudyPackage {
        var payload: ByteArray? = null
        var expected: String? = null
        var total = 0L
        ZipInputStream(context.contentResolver.openInputStream(uri) ?: error("No se puede abrir el paquete")).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                check(++entries <= 8) { "Demasiados archivos en el paquete" }
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= 32L * 1024 * 1024) { "Paquete demasiado grande" }
                    output.write(buffer, 0, count)
                }
                when (entry.name) {
                    "study.json" -> { check(payload == null); payload = output.toByteArray() }
                    "study.sha256" -> { check(expected == null); expected = output.toString("UTF-8").trim() }
                }
            }
        }
        val bytes = payload ?: error("Falta study.json")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == expected) { "Paquete incompleto o dañado" }
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        check(root.getInt("formatVersion") == 1) { "Actualiza la app para abrir este formato" }
        val items = root.getJSONArray("segments")
        check(items.length() in 1..20000) { "Cantidad de subtítulos inválida" }
        var previous = -1L
        val segments = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val start = item.getLong("startMillis")
            val end = item.getLong("endMillis")
            check(start >= 0 && start >= previous && end > start) { "Tiempos inválidos" }
            previous = start
            SubtitleSegment(start, end, item.getString("japanese"), item.getString("spanish"), item.optString("reading"))
        }
        return ImportedStudyPackage(root.optString("videoId"), root.optString("videoFilename"),
            root.optLong("durationMillis"), segments)
    }

    suspend fun verifyVideo(context: Context, uri: Uri, study: ImportedStudyPackage, progress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            check(study.videoId.matches(Regex("[a-fA-F0-9]{64}"))) {
                "El paquete no identifica su vídeo. Regénéralo con el procesador de PC."
            }
            check(uri.scheme in setOf("content", "file")) {
                "Para verificar este paquete, abre el archivo de vídeo original, no una URL de reproducción."
            }
            val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            val hash = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastPercent = -2
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                    read += count
                    val percent = if (size > 0) (read * 100 / size).toInt().coerceIn(0, 100) else -1
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                }
            } ?: error("No se puede leer el vídeo para verificarlo. Descárgalo de OneDrive y vuelve a abrirlo.")
            val actual = hash.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(study.videoId, ignoreCase = true)) {
                "Este paquete pertenece a ${study.videoFilename.ifBlank { "otro archivo de vídeo" }}. " +
                    "El contenido no coincide con el vídeo abierto; no se ha importado."
            }
            progress(100)
    }
}
