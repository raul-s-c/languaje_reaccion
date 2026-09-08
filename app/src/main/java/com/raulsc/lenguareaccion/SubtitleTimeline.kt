package com.raulsc.lenguareaccion

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

@Composable
internal fun SubtitleTimeline(
    segments: List<SubtitleSegment>, position: Long, offset: Long,
    onOffset: (Long) -> Unit, onSeek: (Long) -> Unit, onSave: () -> Unit,
    saved: Boolean, message: String?, modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val compact = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() < 650.dp || LocalWindowInfo.current.containerSize.width.toDp() < 840.dp }
    var actionsExpanded by remember(compact) { mutableStateOf(!compact) }
    val active = segments.indexOfLast { subtitleIsActive(position, it.startMillis, it.endMillis, offset) }
    val next = segments.indexOfFirst { it.startMillis + offset > position }
    LaunchedEffect(active, next, follow) {
        val target = if (active >= 0) active else next
        if (follow && target >= 0) list.animateScrollToItem(target)
    }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Subtítulos · ${segments.size}", style = MaterialTheme.typography.titleLarge)
        Text("Vídeo: ${subtitleTime(position)} · ajuste: ${offset / 1000.0} s")
        if (compact) TextButton(onClick = { actionsExpanded = !actionsExpanded }) {
            Text(if (actionsExpanded) "Ver listado" else "Ajustar sincronización")
        }
        if (actionsExpanded) Column(
            if (compact) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier,
        ) {
        Text("Si aparecen tarde, adelanta. Si aparecen antes de la voz, retrasa.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onOffset((offset - 1000).coerceAtLeast(-300_000)) }) { Text("Adelantar 1 s") }
            TextButton(onClick = { onOffset((offset + 1000).coerceAtMost(300_000)) }) { Text("Retrasar 1 s") }
            TextButton(onClick = { onOffset((offset - 100).coerceAtLeast(-300_000)) }) { Text("−0,1 s") }
            TextButton(onClick = { onOffset((offset + 100).coerceAtMost(300_000)) }) { Text("+0,1 s") }
            TextButton(onClick = { onOffset(0) }) { Text("Cero") }
        }
        Button(onClick = onSave, enabled = !saved) { Text(if (saved) "Sincronización guardada" else "Guardar sincronización") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Seguir reproducción") })
        if (segments.isEmpty()) Text("Importa el paquete del vídeo o genera los subtítulos en la pestaña Estudio.")
        LazyColumn(state = list, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(segments) { index, segment ->
                Column(Modifier.fillMaxWidth().background(
                    if (index == active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ).padding(10.dp)) {
                    Text("${index + 1} · ${subtitleTime(segment.startMillis + offset)} → ${subtitleTime(segment.endMillis + offset)}",
                        style = MaterialTheme.typography.labelLarge)
                    Text("Original: ${subtitleTime(segment.startMillis)}", style = MaterialTheme.typography.bodySmall)
                    Text(segment.japanese)
                    if (segment.spanish.isNotBlank()) Text(segment.spanish)
                    FlowRow {
                        TextButton(onClick = { follow = false; onSeek((segment.startMillis + offset).coerceAtLeast(0)) }) { Text("Ir a frase") }
                        TextButton(onClick = { onOffset((position - segment.startMillis).coerceIn(-300_000, 300_000)) }) { Text("Debe empezar ahora") }
                    }
                }
            }
        }
    }
}

internal fun subtitleTime(milliseconds: Long): String {
    val sign = if (milliseconds < 0) "−" else ""
    val value = kotlin.math.abs(milliseconds)
    return "%s%d:%02d.%03d".format(java.util.Locale.ROOT, sign, value / 60_000, value / 1000 % 60, value % 1000)
}
