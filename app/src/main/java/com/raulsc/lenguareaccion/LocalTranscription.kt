package com.raulsc.lenguareaccion

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SubtitleSegment(
    val startMillis: Long,
    val endMillis: Long,
    val japanese: String,
    val spanish: String = "",
    val reading: String = "",
)

sealed interface TranscriptionState {
    data object Idle : TranscriptionState
    data class DownloadingModel(val model: WhisperModel, val percent: Int) : TranscriptionState
    data class Ready(val model: WhisperModel) : TranscriptionState
    data class ExtractingAudio(val percent: Int) : TranscriptionState
    data class Transcribing(val percent: Int) : TranscriptionState
    data class Enriching(val percent: Int) : TranscriptionState
    data class Completed(val segments: List<SubtitleSegment>, val elapsedMillis: Long) : TranscriptionState
    data class Failed(val message: String) : TranscriptionState
}

class LocalTranscriptionController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val modelStore = WhisperModelStore(appContext)
    private val preferences = appContext.getSharedPreferences("local_transcription", Context.MODE_PRIVATE)
    private val transcriptStore = TranscriptStore(appContext)
    private val secretStore = OpenAiSecretStore(appContext)

    var selectedModel by mutableStateOf(loadSelectedModel())
        private set

    var state by mutableStateOf<TranscriptionState>(initialState())
        private set

    fun isInstalled(model: WhisperModel = selectedModel): Boolean = modelStore.isInstalled(model)

    fun hasOpenAiKey(): Boolean = secretStore.hasKey()

    fun saveOpenAiKey(value: String) {
        secretStore.saveKey(value)
    }

    fun clearOpenAiKey() {
        secretStore.clear()
    }

    fun selectModel(model: WhisperModel) {
        selectedModel = model
        preferences.edit().putString("model", model.name).apply()
        state = if (modelStore.isInstalled(model)) TranscriptionState.Ready(model) else TranscriptionState.Idle
    }

    fun downloadSelectedModel() {
        val model = selectedModel
        state = TranscriptionState.DownloadingModel(model, 0)
        ProcessingService.start(appContext, "Descargando ${model.label}")
        scope.launch {
            try {
                runCatching {
                    modelStore.download(model) { percent ->
                        scope.launch { state = TranscriptionState.DownloadingModel(model, percent) }
                    }
                }.onSuccess {
                    state = TranscriptionState.Ready(model)
                }.onFailure { error ->
                    state = TranscriptionState.Failed(error.readableMessage("No se pudo descargar el modelo"))
                }
            } finally {
                ProcessingService.stop(appContext)
            }
        }
    }

    fun transcribe(videoUri: Uri) {
        if (!modelStore.isInstalled(selectedModel)) {
            state = TranscriptionState.Failed("Descarga primero el modelo ${selectedModel.label}")
            return
        }
        scope.launch {
            val started = System.currentTimeMillis()
            ProcessingService.start(appContext, "Transcribiendo audio japonés")
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LenguaReaccion::LocalTranscription",
            ).apply { acquire(3 * 60 * 60 * 1_000L) }
            var pcmFile: File? = null
            try {
                state = TranscriptionState.ExtractingAudio(0)
                val audio = AudioExtractor.extractJapaneseSpeech(appContext, videoUri) { percent ->
                    scope.launch { state = TranscriptionState.ExtractingAudio(percent) }
                }
                pcmFile = audio.pcmFile
                val segments = LocalWhisperTranscriber.transcribe(
                    pcmFile = audio.pcmFile,
                    modelFile = modelStore.file(selectedModel),
                ) { percent ->
                    scope.launch { state = TranscriptionState.Transcribing(percent) }
                }
                transcriptStore.save(videoUri, selectedModel, segments)
                state = TranscriptionState.Completed(
                    segments = segments,
                    elapsedMillis = System.currentTimeMillis() - started,
                )
            } catch (error: Exception) {
                state = TranscriptionState.Failed(error.readableMessage("La transcripción ha fallado"))
            } finally {
                pcmFile?.delete()
                if (wakeLock.isHeld) wakeLock.release()
                ProcessingService.stop(appContext)
            }
        }
    }

    fun loadLastTranscript(): List<SubtitleSegment> = transcriptStore.load()

    fun importPackage(uri: Uri, videoUri: Uri) {
        scope.launch {
            try {
                val segments = withContext(Dispatchers.IO) { StudyPackage.read(appContext, uri) }
                withContext(Dispatchers.IO) { transcriptStore.save(videoUri, selectedModel, segments) }
                state = TranscriptionState.Completed(segments, 0L)
            } catch (error: Exception) {
                state = TranscriptionState.Failed("No se pudo importar: ${error.message}")
            }
        }
    }

    fun enrichWithOpenAi() {
        val segments = (state as? TranscriptionState.Completed)?.segments ?: transcriptStore.load()
        if (segments.isEmpty()) {
            state = TranscriptionState.Failed("No hay una transcripción que traducir")
            return
        }
        val apiKey = secretStore.readKey()
        if (apiKey.isBlank()) {
            state = TranscriptionState.Failed("Configura primero tu clave de OpenAI")
            return
        }
        scope.launch {
            ProcessingService.start(appContext, "Traduciendo con GPT-5.4 Mini")
            try {
                runCatching {
                    OpenAiStudyService.enrich(apiKey, segments) { percent ->
                        scope.launch { state = TranscriptionState.Enriching(percent) }
                    }
                }.onSuccess { enriched ->
                    transcriptStore.save(Uri.EMPTY, selectedModel, enriched)
                    state = TranscriptionState.Completed(enriched, 0L)
                }.onFailure { error ->
                    state = TranscriptionState.Failed(error.readableMessage("No se pudo completar el estudio con IA"))
                }
            } finally {
                ProcessingService.stop(appContext)
            }
        }
    }

    fun clearFailure() {
        state = if (isInstalled()) TranscriptionState.Ready(selectedModel) else TranscriptionState.Idle
    }

    fun close() {
        scope.cancel()
    }

    private fun initialState(): TranscriptionState {
        val saved = transcriptStore.load()
        return when {
            saved.isNotEmpty() -> TranscriptionState.Completed(saved, 0L)
            modelStore.isInstalled(selectedModel) -> TranscriptionState.Ready(selectedModel)
            else -> TranscriptionState.Idle
        }
    }

    private fun loadSelectedModel(): WhisperModel = runCatching {
        WhisperModel.valueOf(
            preferences.getString("model", WhisperModel.BASE_Q5_1.name)
                ?: WhisperModel.BASE_Q5_1.name,
        )
    }.getOrDefault(WhisperModel.BASE_Q5_1)
}

private object LocalWhisperTranscriber {
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_SECONDS = 29
    private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS

    suspend fun transcribe(
        pcmFile: File,
        modelFile: File,
        progress: (Int) -> Unit,
    ): List<SubtitleSegment> = withContext(Dispatchers.IO) {
        val context = WhisperContext.createContextFromFile(modelFile.absolutePath)
        try {
            val results = mutableListOf<SubtitleSegment>()
            val byteBuffer = ByteArray(CHUNK_SAMPLES * 2)
            var processedSamples = 0L
            BufferedInputStream(pcmFile.inputStream(), byteBuffer.size).use { input ->
                while (true) {
                    val count = input.readChunk(byteBuffer)
                    if (count <= 0) break
                    val shortBuffer = ByteBuffer.wrap(byteBuffer, 0, count)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                    val samples = FloatArray(shortBuffer.remaining()) { shortBuffer.get() / 32768f }
                    val offsetMillis = processedSamples * 1_000L / SAMPLE_RATE
                    if (containsSpeechLikeAudio(samples)) {
                        context.transcribeSegments(samples).forEach { segment ->
                            val text = segment.text.trim()
                            if (text.isNotEmpty() && !text.startsWith("[")) {
                                results += SubtitleSegment(
                                    startMillis = offsetMillis + segment.startMillis,
                                    endMillis = offsetMillis + segment.endMillis,
                                    japanese = text,
                                )
                            }
                        }
                    }
                    processedSamples += samples.size
                    progress(((processedSamples * 2L * 100L) / pcmFile.length()).toInt().coerceIn(0, 100))
                }
            }
            progress(100)
            results
        } finally {
            context.release()
        }
    }

    /**
     * Conservatively skips digital silence and almost-silent stretches before Whisper sees them.
     * Besides saving minutes of CPU, this prevents the common hallucinations produced from silence.
     */
    private fun containsSpeechLikeAudio(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return false
        val window = SAMPLE_RATE / 50 // 20 ms
        var activeWindows = 0
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + window, samples.size)
            var sumSquares = 0.0
            var peak = 0f
            for (index in offset until end) {
                val absolute = kotlin.math.abs(samples[index])
                peak = maxOf(peak, absolute)
                sumSquares += samples[index] * samples[index]
            }
            val rms = kotlin.math.sqrt(sumSquares / (end - offset)).toFloat()
            if (rms >= 0.006f || peak >= 0.025f) {
                activeWindows++
                if (activeWindows >= 5) return true
            }
            offset = end
        }
        return false
    }
}

suspend fun runWhisperSelfTest(context: Context, modelType: WhisperModel): String = withContext(Dispatchers.IO) {
    val model = WhisperModelStore(context).file(modelType)
    check(model.isFile) { "Modelo ${modelType.label} no instalado" }
    val started = System.currentTimeMillis()
    val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "LenguaReaccion::WhisperSelfTest",
    ).apply { acquire(10 * 60 * 1_000L) }
    val whisper = WhisperContext.createContextFromFile(model.absolutePath)
    try {
        val samples = FloatArray(16_000 * 3) { index ->
            (kotlin.math.sin(2.0 * Math.PI * 440.0 * index / 16_000.0) * 0.08).toFloat()
        }
        val segments = whisper.transcribeSegments(samples)
        "OK model=${modelType.name} modelBytes=${model.length()} segments=${segments.size} " +
            "elapsedMs=${System.currentTimeMillis() - started}"
    } finally {
        whisper.release()
        if (wakeLock.isHeld) wakeLock.release()
    }
}

private class TranscriptStore(context: Context) {
    private val file = File(context.filesDir, "last-transcript.json")

    fun save(uri: Uri, model: WhisperModel, segments: List<SubtitleSegment>) {
        val persistedUri = if (uri == Uri.EMPTY && file.isFile) {
            runCatching { JSONObject(file.readText()).optString("videoUri") }.getOrDefault("")
        } else {
            uri.toString()
        }
        val root = JSONObject()
            .put("videoUri", persistedUri)
            .put("model", model.name)
            .put("createdAt", System.currentTimeMillis())
            .put("segments", JSONArray().apply {
                segments.forEach { segment ->
                    put(
                        JSONObject()
                            .put("startMillis", segment.startMillis)
                            .put("endMillis", segment.endMillis)
                            .put("japanese", segment.japanese)
                            .put("spanish", segment.spanish)
                            .put("reading", segment.reading),
                    )
                }
            })
        file.writeText(root.toString())
    }

    fun load(): List<SubtitleSegment> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONObject(file.readText()).getJSONArray("segments")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    SubtitleSegment(
                        startMillis = item.getLong("startMillis"),
                        endMillis = item.getLong("endMillis"),
                        japanese = item.getString("japanese"),
                        spanish = item.optString("spanish"),
                        reading = item.optString("reading"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun BufferedInputStream.readChunk(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val count = read(buffer, total, buffer.size - total)
        if (count < 0) break
        total += count
    }
    return total - (total % 2)
}

private fun Throwable.readableMessage(prefix: String): String =
    "$prefix: ${message ?: javaClass.simpleName}"
