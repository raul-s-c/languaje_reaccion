package com.raulsc.lenguareaccion

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class SavedTranscript(val segments: List<SubtitleSegment>, val source: String)

/** Separate, atomic records: reading one episode can never fall back to another episode. */
internal class TranscriptStore(context: Context) {
    private val directory = File(context.filesDir, "transcripts").apply { mkdirs() }
    private val legacy = File(context.filesDir, "last-transcript.json")

    @Synchronized
    fun save(uri: Uri, model: WhisperModel, segments: List<SubtitleSegment>, source: String = "Generados desde el audio de este vídeo") {
        require(uri != Uri.EMPTY)
        val key = playbackKey(uri.toString())
        val root = JSONObject().put("videoKey", key).put("model", model.name).put("source", source)
            .put("segments", JSONArray().apply { segments.forEach { segment ->
                put(JSONObject().put("startMillis", segment.startMillis).put("endMillis", segment.endMillis)
                    .put("japanese", segment.japanese).put("spanish", segment.spanish).put("reading", segment.reading))
            } })
        val file = AtomicFile(File(directory, "$key.json"))
        val stream = file.startWrite()
        try { stream.write(root.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    @Synchronized
    fun load(uri: Uri): SavedTranscript? = runCatching {
        if (uri == Uri.EMPTY) return null
        val key = playbackKey(uri.toString())
        val file = AtomicFile(File(directory, "$key.json"))
        val root = if (file.baseFile.exists()) {
            JSONObject(file.openRead().bufferedReader().use { it.readText() }).also {
                check(it.getString("videoKey") == key)
            }
        } else {
            if (!legacy.isFile) return null
            JSONObject(legacy.readText()).also {
                if (it.optString("videoUri") != uri.toString()) return null
                it.put("source", "Subtítulos antiguos sin verificar: vuelve a importar el paquete de este vídeo")
            }
        }
        val items = root.getJSONArray("segments")
        SavedTranscript(List(items.length()) { index ->
            val item = items.getJSONObject(index)
            SubtitleSegment(item.getLong("startMillis"), item.getLong("endMillis"), item.getString("japanese"),
                item.optString("spanish"), item.optString("reading"))
        }, root.optString("source", "Subtítulos de este vídeo"))
    }.getOrNull()
}
