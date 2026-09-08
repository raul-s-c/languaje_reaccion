package com.raulsc.lenguareaccion

internal fun playbackKey(uri: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Clamp the shared delta, not the individual values, to preserve their separation. */
internal fun shiftTogether(subtitles: Long, audio: Long, delta: Long): Pair<Long, Long> {
    val allowed = delta.coerceIn(maxOf(-300_000L - subtitles, -5_000L - audio),
        minOf(300_000L - subtitles, 5_000L - audio))
    return (subtitles + allowed) to (audio + allowed)
}

internal fun audioDelayMicros(milliseconds: Long): Long = milliseconds.coerceIn(-5_000L, 5_000L) * 1_000L

internal fun subtitleIsActive(position: Long, start: Long, end: Long, offset: Long): Boolean =
    position - offset >= start && position - offset < end

internal fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
