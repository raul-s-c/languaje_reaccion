package com.raulsc.lenguareaccion

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Bounded parsing without extraction, so archive paths never become filesystem paths. */
object StudyPackage {
    fun read(context: Context, uri: Uri): List<SubtitleSegment> {
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
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val start = item.getLong("startMillis")
            val end = item.getLong("endMillis")
            check(start >= previous && end > start) { "Tiempos inválidos" }
            previous = start
            SubtitleSegment(start, end, item.getString("japanese"), item.getString("spanish"), item.optString("reading"))
        }
    }
}
