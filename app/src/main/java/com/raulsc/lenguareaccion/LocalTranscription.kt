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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    data class Completed(val segments: List<SubtitleSegment>, val elapsedMillis: Long, val source: String = "") : TranscriptionState
    data class Importing(val percent: Int) : TranscriptionState
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

    var currentVideo by mutableStateOf<Uri?>(null)
        private set
    var importRevision by mutableStateOf(0L)
        private set
    private var generation = 0L
    private var importJob: Job? = null

    var state by mutableStateOf<TranscriptionState>(TranscriptionState.Idle)
        private set

    fun selectVideo(uri: Uri?, reload: Boolean = false) {
        if (currentVideo == uri && !reload) return
        importJob?.cancel()
        currentVideo = uri
        val ticket = ++generation
        state = readyState()
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { transcriptStore.load(uri) }
            if (ticket == generation && saved != null) state = TranscriptionState.Completed(saved.segments, 0, saved.source)
        }
    }

    private fun publish(ticket: Long, value: TranscriptionState) {
        if (generation == ticket) state = value
    }
    private fun readyState(): TranscriptionState =
        if (modelStore.isInstalled(selectedModel)) TranscriptionState.Ready(selectedModel) else TranscriptionState.Idle

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
        if (state !is TranscriptionState.Completed) state = readyState()
    }

    fun downloadSelectedModel() {
        val model = selectedModel
        val ticket = ++generation
        state = TranscriptionState.DownloadingModel(model, 0)
        ProcessingService.start(appContext, "Descargando ${model.label}")
        scope.launch {
            try {
                runCatching {
                    modelStore.download(model) { percent ->
                        scope.launch { publish(ticket, TranscriptionState.DownloadingModel(model, percent)) }
                    }
                }.onSuccess {
                    publish(ticket, readyState())
                }.onFailure { error ->
                    publish(ticket, TranscriptionState.Failed(error.readableMessage("No se pudo descargar el modelo")))
                }
            } finally {
                ProcessingService.stop(appContext)
            }
        }
    }

    fun transcribe(videoUri: Uri) {
        selectVideo(videoUri)
        val ticket = ++generation
        val model = selectedModel
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
                    scope.launch { publish(ticket, TranscriptionState.ExtractingAudio(percent)) }
                }
                pcmFile = audio.pcmFile
                val segments = LocalWhisperTranscriber.transcribe(
                    pcmFile = audio.pcmFile,
                    modelFile = modelStore.file(model),
                ) { percent ->
                    scope.launch { publish(ticket, TranscriptionState.Transcribing(percent)) }
                }
                if (ticket != generation) return@launch
                withContext(Dispatchers.IO) { transcriptStore.save(videoUri, model, segments) }
                publish(ticket, TranscriptionState.Completed(segments, System.currentTimeMillis() - started, "Generados desde el audio de este vídeo"))
            } catch (error: Exception) {
                publish(ticket, TranscriptionState.Failed(error.readableMessage("La transcripción ha fallado")))
            } finally {
                pcmFile?.delete()
                if (wakeLock.isHeld) wakeLock.release()
                ProcessingService.stop(appContext)
            }
        }
    }

    fun loadLastTranscript(): List<SubtitleSegment> = (state as? TranscriptionState.Completed)?.segments.orEmpty()

    fun importPackage(uri: Uri, videoUri: Uri) {
        if (videoUri != currentVideo) {
            state = TranscriptionState.Failed("El vídeo cambió mientras elegías el paquete. Vuelve a importarlo para el vídeo abierto.")
            return
        }
        importJob?.cancel()
        val ticket = ++generation
        val model = selectedModel
        state = TranscriptionState.Importing(-1)
        importJob = scope.launch {
            try {
                val study = withContext(Dispatchers.IO) { StudyPackage.readPackage(appContext, uri) }
                StudyPackage.verifyVideo(appContext, videoUri, study) { percent ->
                    scope.launch { publish(ticket, TranscriptionState.Importing(percent)) }
                }
                if (ticket != generation) return@launch
                val source = "Paquete verificado: ${study.videoFilename}"
                withContext(Dispatchers.IO) {
                    transcriptStore.save(videoUri, model, study.segments, source)
                    check(appContext.getSharedPreferences("playback_sync", Context.MODE_PRIVATE).edit()
                        .remove("sub_${playbackKey(videoUri.toString())}").commit()) { "No se pudo restablecer el ajuste de subtítulos" }
                }
                if (ticket == generation) {
                    state = TranscriptionState.Completed(study.segments, 0L, source)
                    importRevision++
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publish(ticket, TranscriptionState.Failed("No se pudo importar: ${error.message}"))
            }
        }
    }

    fun enrichWithOpenAi() {
        val videoUri = currentVideo ?: return
        val completed = state as? TranscriptionState.Completed
        val segments = completed?.segments.orEmpty()
        if (segments.isEmpty()) {
            state = TranscriptionState.Failed("No hay una transcripción que traducir")
            return
        }
        val apiKey = secretStore.readKey()
        if (apiKey.isBlank()) {
            state = TranscriptionState.Failed("Configura primero tu clave de OpenAI")
            return
        }
        val ticket = ++generation
        val model = selectedModel
        scope.launch {
            ProcessingService.start(appContext, "Traduciendo con GPT-5.4 Mini")
            try {
                runCatching {
                    OpenAiStudyService.enrich(apiKey, segments) { percent ->
                        scope.launch { publish(ticket, TranscriptionState.Enriching(percent)) }
                    }
                }.onSuccess { enriched ->
                    if (ticket == generation) {
                        withContext(Dispatchers.IO) { transcriptStore.save(videoUri, model, enriched, completed?.source.orEmpty()) }
                        publish(ticket, TranscriptionState.Completed(enriched, 0L, completed?.source.orEmpty()))
                    }
                }.onFailure { error ->
                    publish(ticket, TranscriptionState.Failed(error.readableMessage("No se pudo completar el estudio con IA")))
                }
            } finally {
                ProcessingService.stop(appContext)
            }
        }
    }

    fun clearFailure() {
        selectVideo(currentVideo, reload = true)
    }

    fun close() {
        scope.cancel()
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
