package com.raulsc.lenguareaccion

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

data class ExtractedAudio(
    val pcmFile: File,
    val sampleCount: Long,
    val durationMillis: Long,
)

private data class AudioTrack(
    val index: Int,
    val format: MediaFormat,
    val mime: String,
    val language: String?,
    val decoderName: String?,
)

@OptIn(UnstableApi::class)
object AudioExtractor {
    private const val TARGET_SAMPLE_RATE = 16_000

    suspend fun extractJapaneseSpeech(
        context: Context,
        uri: Uri,
        progress: (Int) -> Unit,
    ): ExtractedAudio = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "transcription/audio-16khz-mono.pcm")
        target.parentFile?.mkdirs()
        target.delete()

        val extractor = MediaExtractorCompat(context)
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackDescriptions = (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "formato desconocido"
                val language = format.getString(MediaFormat.KEY_LANGUAGE)
                "pista $index: $mime${language?.let { " ($it)" }.orEmpty()}"
            }
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val audioTracks = (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") != true) return@mapNotNull null
                AudioTrack(
                    index = index,
                    format = format,
                    mime = mime,
                    language = format.getString(MediaFormat.KEY_LANGUAGE),
                    decoderName = runCatching { codecList.findDecoderForFormat(format) }.getOrNull(),
                )
            }
            if (audioTracks.isEmpty()) error(
                "No se encontró una pista de audio. Formatos detectados: " +
                    trackDescriptions.ifEmpty { listOf("ninguno") }.joinToString(),
            )
            val selectedTrack = audioTracks
                .filter { it.decoderName != null }
                .sortedByDescending { it.language.isJapaneseLanguage() }
                .firstOrNull()
                ?: error(
                    "El vídeo contiene audio, pero HyperOS no puede decodificar sus códecs: " +
                        audioTracks.joinToString { track ->
                            "${track.mime}${track.language?.let { " ($it)" }.orEmpty()}"
                        },
                )

            val trackFormat = selectedTrack.format
            val mime = selectedTrack.mime
            val durationUs = trackFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
            extractor.selectTrack(selectedTrack.index)

            decoder = runCatching { MediaCodec.createByCodecName(selectedTrack.decoderName!!) }.getOrElse { error ->
                throw IllegalArgumentException(
                    "La pista $mime existe, pero HyperOS no dispone de un decodificador para ese códec",
                    error,
                )
            }
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            var channels = trackFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var sampleRate = trackFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            var outputSamples = 0L
            var lastProgress = -1
            var resampler = PcmResampler(sampleRate, TARGET_SAMPLE_RATE)
            val info = MediaCodec.BufferInfo()

            BufferedOutputStream(target.outputStream(), 256 * 1024).use { output ->
                while (!outputEnded) {
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: error("El decodificador no proporcionó búfer de entrada")
                            inputBuffer.clear()
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    extractor.sampleTime.coerceAtLeast(0L),
                                    0,
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            channels = outputFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            sampleRate = outputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            pcmEncoding = outputFormat.getIntOrDefault(
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT,
                            )
                            resampler = PcmResampler(sampleRate, TARGET_SAMPLE_RATE)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER,
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            if (info.size > 0) {
                                val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                    ?: error("El decodificador no proporcionó audio")
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                outputSamples += resampler.write(
                                    source = outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN),
                                    channels = channels,
                                    encoding = pcmEncoding,
                                    output = output,
                                )
                                if (durationUs > 0) {
                                    val percent = ((info.presentationTimeUs * 100L) / durationUs)
                                        .toInt().coerceIn(0, 100)
                                    if (percent != lastProgress) {
                                        lastProgress = percent
                                        progress(percent)
                                    }
                                }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            check(target.length() > 0L) { "La pista de audio está vacía" }
            progress(100)
            ExtractedAudio(
                pcmFile = target,
                sampleCount = outputSamples,
                durationMillis = outputSamples * 1_000L / TARGET_SAMPLE_RATE,
            )
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }
}

private class PcmResampler(
    sourceRate: Int,
    targetRate: Int,
) {
    private val sourceStep = sourceRate.toDouble() / targetRate.toDouble()
    private var sourceCursor = 0L
    private var nextOutputPosition = 0.0

    fun write(
        source: ByteBuffer,
        channels: Int,
        encoding: Int,
        output: BufferedOutputStream,
    ): Int {
        require(channels > 0) { "Número de canales de audio inválido" }
        val mono = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> decodePcm16(source, channels)
            AudioFormat.ENCODING_PCM_FLOAT -> decodePcmFloat(source, channels)
            else -> error("PCM no compatible: $encoding")
        }
        if (mono.isEmpty()) return 0

        val end = sourceCursor + mono.size
        val samples = ArrayList<Short>((mono.size / sourceStep).toInt() + 2)
        while (nextOutputPosition < end) {
            val local = (nextOutputPosition - sourceCursor).coerceAtLeast(0.0)
            val left = floor(local).toInt().coerceIn(0, mono.lastIndex)
            val right = (left + 1).coerceAtMost(mono.lastIndex)
            val fraction = (local - floor(local)).toFloat()
            val value = mono[left] + (mono[right] - mono[left]) * fraction
            samples += (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            nextOutputPosition += sourceStep
        }
        sourceCursor = end

        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putShort)
        output.write(bytes.array())
        return samples.size
    }

    private fun decodePcm16(source: ByteBuffer, channels: Int): FloatArray {
        val shorts = source.asShortBuffer()
        val frames = shorts.remaining() / channels
        return FloatArray(frames) {
            var sum = 0f
            repeat(channels) { sum += shorts.get() / 32768f }
            sum / channels
        }
    }

    private fun decodePcmFloat(source: ByteBuffer, channels: Int): FloatArray {
        val floats = source.asFloatBuffer()
        val frames = floats.remaining() / channels
        return FloatArray(frames) {
            var sum = 0f
            repeat(channels) { sum += floats.get() }
            (sum / channels).coerceIn(-1f, 1f)
        }
    }
}

private fun MediaFormat.getIntOrDefault(key: String, fallback: Int): Int =
    if (containsKey(key)) getInteger(key) else fallback

private fun MediaFormat.getLongOrDefault(key: String, fallback: Long): Long =
    if (containsKey(key)) getLong(key) else fallback

private fun String?.isJapaneseLanguage(): Boolean =
    this?.lowercase()?.let { it == "ja" || it == "jpn" || it.startsWith("ja-") } == true
