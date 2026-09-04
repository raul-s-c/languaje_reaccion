package com.raulsc.lenguareaccion

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

enum class SubtitleFormat(val extension: String, val mimeType: String) {
    SRT("srt", "application/x-subrip"),
    VTT("vtt", "text/vtt"),
}

object TranscriptExporter {
    fun share(context: Context, segments: List<SubtitleSegment>, format: SubtitleFormat) {
        require(segments.isNotEmpty()) { "No hay subtítulos que exportar" }
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val file = File(directory, "lengua-reaccion.${format.extension}")
        file.writeText(
            when (format) {
                SubtitleFormat.SRT -> toSrt(segments)
                SubtitleFormat.VTT -> toVtt(segments)
            },
        )
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir subtítulos"))
    }

    private fun toSrt(segments: List<SubtitleSegment>): String = buildString {
        segments.forEachIndexed { index, segment ->
            appendLine(index + 1)
            appendLine("${timestamp(segment.startMillis, ',')} --> ${timestamp(segment.endMillis, ',')}")
            appendStudyLines(segment)
            appendLine()
        }
    }

    private fun toVtt(segments: List<SubtitleSegment>): String = buildString {
        appendLine("WEBVTT")
        appendLine()
        segments.forEach { segment ->
            appendLine("${timestamp(segment.startMillis, '.')} --> ${timestamp(segment.endMillis, '.')}")
            appendStudyLines(segment)
            appendLine()
        }
    }

    private fun StringBuilder.appendStudyLines(segment: SubtitleSegment) {
        appendLine(segment.japanese)
        if (segment.reading.isNotBlank()) appendLine(segment.reading)
        if (segment.spanish.isNotBlank()) appendLine(segment.spanish)
    }

    private fun timestamp(value: Long, separator: Char): String {
        val hours = value / 3_600_000L
        val minutes = (value / 60_000L) % 60L
        val seconds = (value / 1_000L) % 60L
        val millis = value % 1_000L
        return "%02d:%02d:%02d%c%03d".format(hours, minutes, seconds, separator, millis)
    }
}
