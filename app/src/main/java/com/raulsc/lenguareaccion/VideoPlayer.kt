package com.raulsc.lenguareaccion

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** One native player survives moving between the study column and the fullscreen box. */
@Composable
internal fun VideoPlayer(
    uri: Uri, onPositionChanged: (Long) -> Unit, avOffset: Long, fullscreen: Boolean,
    seekRequest: Pair<Long, Long>?, segment: SubtitleSegment?, openSync: () -> Unit, toggleFullscreen: () -> Unit, modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val progress = remember { context.getSharedPreferences("playback_progress", Context.MODE_PRIVATE) }
    val key = remember(uri) { playbackKey(uri.toString()) }
    val currentOffset by rememberUpdatedState(avOffset)
    val positionCallback by rememberUpdatedState(onPositionChanged)
    var position by remember(uri) { mutableLongStateOf(progress.getLong(key, 0L)) }
    var duration by remember(uri) { mutableLongStateOf(0L) }
    var playing by remember(uri) { mutableStateOf(false) }
    var started by remember(uri) { mutableStateOf(false) }
    var ended by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var audioWarning by remember(uri) { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(false) }
    var speed by remember(uri) { mutableFloatStateOf(1f) }
    var scrub by remember(uri) { mutableStateOf<Float?>(null) }
    var studySegment by remember(uri) { mutableStateOf<SubtitleSegment?>(null) }
    var showTracks by remember(uri) { mutableStateOf(false) }
    val vlc = remember(uri) {
        LibVLC(context.applicationContext, arrayListOf("--audio-language=ja,jpn", "--no-sub-autodetect-file"))
    }
    val player = remember(vlc) { MediaPlayer(vlc).apply { setUseOrientationFromBounds(true) } }

    DisposableEffect(player) {
        var descriptor: ParcelFileDescriptor? = null
        var restored = false
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    started = true
                    playing = true
                    error = null
                    if (!restored) {
                        if (position > 0L) player.setTime(position)
                        restored = true
                    }
                    player.setSpuTrack(-1)
                    audioWarning = !player.setAudioDelay(audioDelayMicros(currentOffset))
                    player.setRate(speed)
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.EndReached -> {
                    playing = false
                    ended = true
                    position = duration
                    restored = false
                    progress.edit().putLong(key, 0L).apply()
                }
                MediaPlayer.Event.EncounteredError -> {
                    playing = false
                    error = "No se ha podido reproducir el vídeo. Comprueba que el archivo esté descargado o que la URL siga disponible."
                }
            }
        }
        runCatching {
            val media = if (uri.scheme == "content") {
                descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                Media(vlc, requireNotNull(descriptor) { "No se puede abrir el archivo" }.fileDescriptor)
            } else Media(vlc, uri)
            try {
                media.setHWDecoderEnabled(true, false)
                media.addOption(":sub-track=-1")
                player.media = media
            } finally { media.release() }
        }.onFailure { error = "No se puede abrir el vídeo. Vuelve a seleccionarlo con Abrir vídeo." }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                player.pause()
                progress.edit().putLong(key, if (ended) 0L else position).apply()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            progress.edit().putLong(key, if (ended) 0L else position).apply()
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
            descriptor?.close()
            vlc.release()
        }
    }
    LaunchedEffect(player, avOffset) {
        if (started) audioWarning = !player.setAudioDelay(audioDelayMicros(avOffset))
    }
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            if (player.isPlaying) position = player.time.coerceAtLeast(0)
            duration = player.length.coerceAtLeast(0)
            positionCallback(position)
            if (++ticks % 25 == 0) progress.edit().putLong(key, if (ended) 0L else position).apply()
            delay(200)
        }
    }
    LaunchedEffect(controls, playing, fullscreen, showTracks, scrub) {
        if (controls && playing && fullscreen && !showTracks && scrub == null) { delay(4500); controls = false }
    }
    fun seek(target: Long) {
        if (player.isSeekable) {
            position = target.coerceIn(0L, player.length.coerceAtLeast(0L))
            player.setTime(position)
            positionCallback(position)
        }
    }
    LaunchedEffect(player, seekRequest, started) {
        seekRequest?.let {
            if (!started || ended) {
                if (ended) { player.stop(); ended = false; position = 0L }
                player.play()
            }
            if (started) seek(it.second)
        }
    }
    studySegment?.let { selected ->
        AlertDialog(onDismissRequest = { studySegment = null }, title = { Text("Estudiar frase") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { SubtitlePreview(selected) } },
            confirmButton = { TextButton(onClick = { studySegment = null }) { Text("Volver al vídeo") } })
    }
    if (showTracks) {
        AlertDialog(onDismissRequest = { showTracks = false }, title = { Text("Pista de audio") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                player.audioTracks?.filter { it.id >= 0 }?.forEach { track ->
                    TextButton(onClick = {
                        if (player.setAudioTrack(track.id)) {
                            audioWarning = !player.setAudioDelay(audioDelayMicros(currentOffset))
                            showTracks = false
                        }
                    }) { Text((if (player.audioTrack == track.id) "✓ " else "") + track.name) }
                }
            } }, confirmButton = { TextButton(onClick = { showTracks = false }) { Text("Cerrar") } })
    }
    LaunchedEffect(fullscreen) { controls = true }
    Box(modifier.background(Color.Black).clipToBounds()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            VLCVideoLayout(ctx).apply { player.attachViews(this, null, false, true) }
        }, update = { view ->
            view.keepScreenOn = playing
            val scale = if (zoom) MediaPlayer.ScaleType.SURFACE_FIT_SCREEN else MediaPlayer.ScaleType.SURFACE_BEST_FIT
            if (player.videoScale != scale) player.videoScale = scale
        })
        Box(Modifier.fillMaxSize().clickable { controls = !controls })
        if (!started && error == null) Text("Pulsa Reproducir para cargar el vídeo",
            Modifier.align(Alignment.Center).padding(16.dp), color = Color.White)
        // These are overlays: they never shrink the available video surface.
        if (controls) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .75f)),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = openSync) { Text("Sincronización", color = Color.White) }
            TextButton(onClick = { zoom = !zoom }) { Text(if (zoom) "Ajustar" else "Llenar (recorta)", color = Color.White) }
            TextButton(onClick = toggleFullscreen) { Text(if (fullscreen) "Salir" else "Ampliar", color = Color.White) }
        }
        if (fullscreen && segment != null) Column(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).padding(bottom = if (controls) 118.dp else 20.dp)
                .heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                .background(Color.Black.copy(alpha = .72f)).clickable { player.pause(); studySegment = segment }.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(segment.japanese, color = Color.White, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (segment.reading.isNotBlank()) Text(segment.reading, color = Color(0xFFFFD180), textAlign = TextAlign.Center)
            if (segment.spanish.isNotBlank()) Text(segment.spanish, color = Color.White, textAlign = TextAlign.Center)
        }
        if (controls) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .8f)).padding(horizontal = 12.dp)) {
            Slider(value = scrub ?: position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { scrub = it }, onValueChangeFinished = { scrub?.let { seek(it.toLong()) }; scrub = null },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = started && duration > 0 && player.isSeekable)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { seek(position - 10_000) }) { Text("−10 s", color = Color.White) }
                TextButton(enabled = error == null, onClick = {
                    if (playing) player.pause() else {
                        if (ended) { player.stop(); ended = false; position = 0L }
                        player.play()
                    }
                }) {
                    Text(if (playing) "Pausa" else "Reproducir", color = Color.White)
                }
                TextButton(onClick = { seek(position + 10_000) }) { Text("+10 s", color = Color.White) }
                Text("${playbackTime(position)} / ${playbackTime(duration)}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = {
                    val next = when (speed) { .75f -> 1f; 1f -> 1.25f; 1.25f -> 1.5f; else -> .75f }
                    player.setRate(next)
                    speed = next
                }) { Text("${speed}×", color = Color.White) }
                TextButton(enabled = started, onClick = { showTracks = true }) { Text("Audio", color = Color.White) }
            }
        }
        (error ?: if (audioWarning) "No se pudo aplicar el retardo de audio. Reanuda la reproducción y vuelve a intentarlo." else null)?.let {
            Text(it, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .85f)).padding(16.dp), color = Color.White)
        }
    }
}
