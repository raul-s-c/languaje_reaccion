package com.raulsc.lenguareaccion

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var previousCrash by remember { mutableStateOf(CrashReporter.read(context)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        videoUri = uri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 18.dp),
    ) {
        AppHeader()
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

        if (wide) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                PlayerPane(
                    videoUri = videoUri,
                    chooseVideo = { picker.launch(arrayOf("video/*")) },
                    modifier = Modifier.weight(1.65f),
                )
                StudyPane(modifier = Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlayerPane(videoUri, { picker.launch(arrayOf("video/*")) }, Modifier.fillMaxWidth())
                StudyPane(Modifier.fillMaxWidth())
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
private fun PlayerPane(videoUri: Uri?, chooseVideo: () -> Unit, modifier: Modifier = Modifier) {
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
                OutlinedButton(onClick = chooseVideo) {
                    Text(if (videoUri == null) "Abrir vídeo" else "Cambiar")
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
                VideoPlayer(videoUri)
            }

            Spacer(Modifier.height(14.dp))
            SubtitlePreview()
        }
    }
}

@Composable
private fun VideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    AndroidView(
        modifier = Modifier.fillMaxWidth().height(340.dp),
        factory = { PlayerView(it).apply { this.player = player } },
        update = { it.player = player },
    )
}

@Composable
private fun SubtitlePreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("字幕を自動で生成します", style = MaterialTheme.typography.titleLarge)
        Text("じまく　　じどう　　せいせい", color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(6.dp))
        Text("Generaremos los subtítulos automáticamente.")
    }
}

@Composable
private fun StudyPane(modifier: Modifier = Modifier) {
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
            StatusItem("01", "Vídeo", "Local disponible · Plex en preparación")
            HorizontalDivider()
            StatusItem("02", "Transcripción japonesa", "Servicio del PC en preparación")
            HorizontalDivider()
            StatusItem("03", "Furigana y diccionario", "JMdict + análisis morfológico")
            HorizontalDivider()
            StatusItem("04", "Traducción española", "Contextual y editable")
            Spacer(Modifier.height(6.dp))
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Generar subtítulos")
            }
            Text(
                "Esta primera compilación valida instalación, reproducción local y actualizaciones. " +
                    "El procesamiento japonés llegará en las siguientes versiones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
    }
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
