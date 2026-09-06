package com.raulsc.lenguareaccion

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("whisper_self_test", false)) {
            val model = runCatching {
                WhisperModel.valueOf(
                    intent.getStringExtra("whisper_self_test_model") ?: WhisperModel.TINY_Q5_1.name,
                )
            }.getOrDefault(WhisperModel.TINY_Q5_1)
            ProcessingService.startSelfTest(applicationContext, model)
        }
        setContent {
            val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            LenguaReaccionTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReactorHome()
                }
            }
        }
    }
}

@Composable
private fun ReactorHome() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val wide = with(density) { LocalWindowInfo.current.containerSize.width.toDp() >= 840.dp }
    val preferences = remember {
        context.getSharedPreferences("video", android.content.Context.MODE_PRIVATE)
    }
    var videoUri by remember {
        mutableStateOf(preferences.getString("uri", null)?.let(Uri::parse))
    }
    var showVideoUrlDialog by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    BackHandler(fullscreen) { fullscreen = false }
    DisposableEffect(fullscreen) {
        val window = (context as? android.app.Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        } else bars?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val transcriptionController = remember { LocalTranscriptionController(context) }
    val transcriptionState = transcriptionController.state
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && videoUri != null) transcriptionController.importPackage(uri, videoUri!!)
    }
    var previousCrash by remember { mutableStateOf(CrashReporter.read(context)) }
    DisposableEffect(transcriptionController) { onDispose { transcriptionController.close() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        videoUri = uri
        preferences.edit().putString("uri", uri.toString()).apply()
    }
    if (showVideoUrlDialog) {
        VideoUrlDialog(
            dismiss = { showVideoUrlDialog = false },
            open = { uri ->
                videoUri = uri
                // Plex tokens often travel in the query string; do not persist them in plain text.
                preferences.edit().remove("uri").apply()
                showVideoUrlDialog = false
            },
        )
    }

    val playerContent = remember {
        movableContentOf<Uri?, TranscriptionState, Modifier, Boolean> { uri, state, layout, expanded ->
            PlayerPane(uri, state, { picker.launch(arrayOf("video/*")) },
                { showVideoUrlDialog = true }, layout, expanded, { fullscreen = !fullscreen })
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = if (fullscreen) 0.dp else if (wide) 28.dp else 16.dp, vertical = if (fullscreen) 0.dp else 18.dp),
    ) {
        if (!fullscreen) {
        AppHeader()
        TextButton(enabled = videoUri != null, onClick = { packagePicker.launch(arrayOf("*/*")) }) {
            Text("Importar paquete PC para el vídeo abierto (.lrpack)")
        }
        previousCrash?.let { crash ->
            Spacer(Modifier.height(12.dp))
            CrashNotice(
                crash = crash,
                dismiss = {
                    CrashReporter.clear(context)
                    previousCrash = null
                },
            )
        }
        Spacer(Modifier.height(18.dp))
        }

        if (fullscreen) {
            playerContent(videoUri, transcriptionState, Modifier.fillMaxSize(), true)
        } else if (wide) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                playerContent(videoUri, transcriptionState, Modifier.weight(1.65f), false)
                StudyPane(
                    videoUri = videoUri,
                    controller = transcriptionController,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                playerContent(videoUri, transcriptionState, Modifier.fillMaxWidth(), false)
                StudyPane(videoUri, transcriptionController, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AppHeader() {
    val density = LocalDensity.current
    val compact = with(density) { LocalWindowInfo.current.containerSize.width.toDp() < 600.dp }
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppTitle()
            UpdateControl()
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AppTitle()
            UpdateControl()
        }
    }
}

@Composable
private fun AppTitle() {
    Column {
        Text("言葉 REACCIÓN", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Japonés → español · versión ${BuildConfig.VERSION_NAME}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun PlayerPane(
    videoUri: Uri?,
    transcriptionState: TranscriptionState,
    chooseVideo: () -> Unit,
    openVideoUrl: () -> Unit,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    toggleFullscreen: () -> Unit = {},
) {
    var playbackPosition by remember(videoUri) { mutableLongStateOf(0L) }
    val segments = (transcriptionState as? TranscriptionState.Completed)?.segments.orEmpty()
    val activeSegment = segments.lastOrNull {
        playbackPosition >= it.startMillis && playbackPosition < it.endMillis
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Reproductor de estudio", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (videoUri == null) "Elige un vídeo japonés para comenzar"
                        else "Vídeo local preparado",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = toggleFullscreen) { Text(if (fullscreen) "Salir" else "Pantalla completa") }
                    if (!fullscreen) {
                    TextButton(onClick = openVideoUrl) { Text("Plex/URL") }
                    OutlinedButton(onClick = chooseVideo) {
                        Text(if (videoUri == null) "Abrir vídeo" else "Cambiar")
                    }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            if (videoUri == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("日本語", color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Tu biblioteca, tus subtítulos, tu ritmo", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            } else {
                VideoPlayer(videoUri, onPositionChanged = { playbackPosition = it },
                    modifier = if (fullscreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(340.dp))
            }

            Spacer(Modifier.height(14.dp))
            SubtitlePreview(activeSegment ?: segments.firstOrNull())
        }
    }
}

@Composable
private fun VideoUrlDialog(dismiss: () -> Unit, open: (Uri) -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Abrir vídeo de Plex o URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pega una URL directa de vídeo o transcodificación de Plex. Puede incluir el token en la propia URL.")
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim(); error = null },
                    label = { Text("http://192.168… o https://…") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { message -> ({ Text(message) }) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val uri = Uri.parse(value)
                if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                    error = "Introduce una URL HTTP o HTTPS completa"
                } else {
                    open(uri)
                }
            }) { Text("Abrir") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun VideoPlayer(uri: Uri, onPositionChanged: (Long) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val progressStore = remember { context.getSharedPreferences("playback_progress", android.content.Context.MODE_PRIVATE) }
    val progressKey = remember(uri) {
        java.security.MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioLanguage("ja").build()
            setMediaItem(MediaItem.fromUri(uri))
            seekTo(progressStore.getLong(progressKey, 0L))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose {
        progressStore.edit().putLong(progressKey, player.currentPosition.coerceAtLeast(0L)).apply()
        player.release()
    } }
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            onPositionChanged(player.currentPosition.coerceAtLeast(0L))
            if (++ticks % 25 == 0) {
                progressStore.edit().putLong(progressKey, player.currentPosition.coerceAtLeast(0L)).apply()
            }
            delay(200)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { PlayerView(it).apply { this.player = player } },
        update = { it.player = player },
    )
}

@Composable
private fun SubtitlePreview(segment: SubtitleSegment?) {
    val tokens by produceState(emptyList<JapaneseToken>(), segment?.japanese) {
        value = if (segment == null) emptyList() else withContext(Dispatchers.Default) {
            runCatching { JapaneseMorphology.analyze(segment.japanese) }.getOrDefault(emptyList())
        }
    }
    var selectedToken by remember(segment?.japanese) { mutableStateOf<JapaneseToken?>(null) }
    selectedToken?.let { token ->
        AlertDialog(
            onDismissRequest = { selectedToken = null },
            title = { Text(token.surface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (token.reading.isNotBlank()) Text("Lectura: ${token.reading}")
                    Text("Forma de diccionario: ${token.baseForm}")
                    if (token.partOfSpeech.isNotBlank()) Text("Categoría: ${token.partOfSpeech}")
                }
            },
            confirmButton = { TextButton(onClick = { selectedToken = null }) { Text("Cerrar") } },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(segment?.japanese ?: "字幕を自動で生成します", style = MaterialTheme.typography.titleLarge)
        if (segment == null) {
            Text("じまく　　じどう　　せいせい", color = MaterialTheme.colorScheme.secondary)
        } else if (segment.reading.isNotBlank()) {
            Text(segment.reading, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(6.dp))
        Text(segment?.spanish?.ifBlank { "Traducción pendiente" }
            ?: "Generaremos los subtítulos automáticamente.")
        if (tokens.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                tokens.forEach { token ->
                    FilterChip(
                        selected = false,
                        onClick = { selectedToken = token },
                        label = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (token.reading.isNotBlank() && token.reading != token.surface) {
                                    Text(token.reading, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(token.surface)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StudyPane(
    videoUri: Uri?,
    controller: LocalTranscriptionController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state = controller.state
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var hasApiKey by remember { mutableStateOf(controller.hasOpenAiKey()) }
    val busy = state is TranscriptionState.DownloadingModel ||
        state is TranscriptionState.ExtractingAudio || state is TranscriptionState.Transcribing ||
        state is TranscriptionState.Enriching

    if (showApiKeyDialog) {
        ApiKeyDialog(
            configured = hasApiKey,
            dismiss = { showApiKeyDialog = false },
            save = { value ->
                controller.saveOpenAiKey(value)
                hasApiKey = true
                showApiKeyDialog = false
            },
            clear = {
                controller.clearOpenAiKey()
                hasApiKey = false
                showApiKeyDialog = false
            },
        )
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Sesión", style = MaterialTheme.typography.titleLarge)
            StatusItem("01", "Vídeo", "Archivo local o enlace directo de Plex")
            HorizontalDivider()
            StatusItem("02", "Transcripción japonesa", "Whisper local · sin enviar audio")
            HorizontalDivider()
            StatusItem("03", "Lecturas y diccionario", "IPADIC local · funciona sin conexión")
            HorizontalDivider()
            StatusItem("04", "Traducción española", "Contextual con GPT-5.4 Mini")
            Spacer(Modifier.height(6.dp))

            Text("Modelo local", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WhisperModel.entries.forEach { model ->
                    FilterChip(
                        selected = controller.selectedModel == model,
                        onClick = { if (!busy) controller.selectModel(model) },
                        label = { Text(model.label) },
                        enabled = !busy,
                    )
                }
            }
            Text(
                controller.selectedModel.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )

            if (!controller.isInstalled()) {
                Button(
                    onClick = controller::downloadSelectedModel,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Descargar modelo")
                }
            }

            TranscriptionStatus(state, controller::clearFailure)

            Button(
                onClick = { videoUri?.let(controller::transcribe) },
                enabled = videoUri != null && controller.isInstalled() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state is TranscriptionState.Completed) "Regenerar subtítulos" else "Generar subtítulos")
            }

            OutlinedButton(
                onClick = { showApiKeyDialog = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (hasApiKey) "Clave OpenAI configurada" else "Configurar clave OpenAI")
            }
            if (state is TranscriptionState.Completed) {
                Button(
                    onClick = controller::enrichWithOpenAi,
                    enabled = hasApiKey && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Corregir y traducir con GPT-5.4 Mini")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { TranscriptExporter.share(context, state.segments, SubtitleFormat.SRT) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Compartir SRT") }
                    OutlinedButton(
                        onClick = { TranscriptExporter.share(context, state.segments, SubtitleFormat.VTT) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Compartir VTT") }
                }
            }
            Text(
                "El audio se extrae y transcribe dentro de esta tablet. El vídeo y el audio temporal " +
                    "no se envían a ningún servidor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun TranscriptionStatus(state: TranscriptionState, clearFailure: () -> Unit) {
    when (state) {
        TranscriptionState.Idle -> Text("Modelo pendiente de descarga", style = MaterialTheme.typography.bodySmall)
        is TranscriptionState.Ready -> Text(
            "Modelo preparado: ${state.model.label}",
            color = MaterialTheme.colorScheme.primary,
        )
        is TranscriptionState.DownloadingModel -> ProgressStatus(
            "Descargando modelo ${state.model.label}",
            state.percent,
        )
        is TranscriptionState.ExtractingAudio -> ProgressStatus("Extrayendo audio", state.percent)
        is TranscriptionState.Transcribing -> ProgressStatus("Reconociendo japonés", state.percent)
        is TranscriptionState.Enriching -> ProgressStatus("Corrigiendo y traduciendo", state.percent)
        is TranscriptionState.Completed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${state.segments.size} segmentos japoneses generados",
                color = MaterialTheme.colorScheme.primary,
            )
            state.segments.take(4).forEach { segment ->
                Text(
                    "${formatMillis(segment.startMillis)}  ${segment.japanese}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.segments.size > 4) {
                Text("… y ${state.segments.size - 4} más", style = MaterialTheme.typography.bodySmall)
            }
        }
        is TranscriptionState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = clearFailure) {
                Text("Cerrar mensaje")
            }
        }
    }
}

@Composable
private fun ApiKeyDialog(
    configured: Boolean,
    dismiss: () -> Unit,
    save: (String) -> Unit,
    clear: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Clave de OpenAI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Se cifra con Android Keystore y nunca se incluye en la APK ni en los registros.")
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    label = { Text("sk-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { message -> ({ Text(message) }) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { save(value) }
                    .onFailure { error = it.message ?: "Clave no válida" }
            }) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                if (configured) TextButton(onClick = clear) { Text("Eliminar") }
                TextButton(onClick = dismiss) { Text("Cancelar") }
            }
        },
    )
}

@Composable
private fun ProgressStatus(label: String, percent: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label · $percent%")
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatMillis(value: Long): String {
    val totalSeconds = value / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun CrashNotice(crash: String, dismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Se recuperó un cierre anterior", style = MaterialTheme.typography.titleSmall)
                Text(
                    crash.lineSequence().firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = dismiss) { Text("Descartar") }
        }
    }
}

@Composable
private fun StatusItem(number: String, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(number, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        }
    }
}

@Composable
private fun UpdateControl() {
    val context = LocalContext.current
    val updater = remember { AppUpdater(context.applicationContext) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    DisposableEffect(updater) { onDispose { updater.shutdown() } }

    Column(horizontalAlignment = Alignment.End) {
        when (val current = state) {
            UpdateState.Idle -> OutlinedButton(onClick = { updater.check { state = it } }) {
                Text("Buscar actualización")
            }
            UpdateState.Checking -> BusyLabel("Comprobando…")
            UpdateState.UpToDate -> {
                Text("Versión actualizada", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { updater.check { state = it } }) { Text("Comprobar") }
            }
            is UpdateState.Available -> Column(horizontalAlignment = Alignment.End) {
                Button(onClick = { updater.download(current.info) { state = it } }) {
                    Text("Actualizar a ${current.info.versionName}")
                }
                if (current.info.notes.isNotBlank()) {
                    Text(current.info.notes, style = MaterialTheme.typography.bodySmall)
                }
            }
            is UpdateState.Downloading -> BusyLabel("Descargando ${current.percent}%")
            is UpdateState.Ready -> Column(horizontalAlignment = Alignment.End) {
                Button(onClick = { updater.install(current.apk) }) {
                    Text("Instalar ${current.info.versionName}")
                }
                Text(
                    "Si Android abre los ajustes, concede el permiso y pulsa de nuevo aquí.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is UpdateState.Failed -> {
                Text(current.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { updater.check { state = it } }) { Text("Reintentar") }
            }
        }
    }
}

@Composable
private fun BusyLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}
