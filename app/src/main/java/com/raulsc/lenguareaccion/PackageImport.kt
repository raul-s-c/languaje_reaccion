package com.raulsc.lenguareaccion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Only searches files/folders the user has already granted this app access to. */
internal object PackageVideos {
    fun rememberPermission(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun name(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") File(uri.path.orEmpty()).name else
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
    }.getOrNull()

    suspend fun find(context: Context, filename: String, current: Uri?): Uri? = withContext(Dispatchers.IO) {
        if (filename.isBlank()) return@withContext null
        val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
        for (uri in (listOfNotNull(current) + grants.filterNot { DocumentsContract.isTreeUri(it) }).distinct()) {
            coroutineContext.ensureActive()
            if (name(context, uri) == filename) return@withContext uri
        }
        for (tree in grants.filter { DocumentsContract.isTreeUri(it) }) {
            val queue = ArrayDeque<String>()
            queue.add(DocumentsContract.getTreeDocumentId(tree))
            val visited = mutableSetOf<String>()
            while (queue.isNotEmpty() && visited.size < 5000) {
                coroutineContext.ensureActive()
                val id = queue.removeFirst()
                if (!visited.add(id)) continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                val found = runCatching {
                    context.contentResolver.query(children, arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                        var match: Uri? = null
                        while (cursor.moveToNext()) {
                            val child = cursor.getString(0)
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(child)
                            else if (cursor.getString(1) == filename) {
                                match = DocumentsContract.buildDocumentUriUsingTree(tree, child)
                                break
                            }
                        }
                        match
                    }
                }.getOrNull()
                if (found != null) return@withContext found
            }
        }
        null
    }
}

@Composable
internal fun PackageImportDialog(packageUri: Uri, currentVideo: Uri?, dismiss: () -> Unit, open: (Uri) -> Unit) {
    val context = LocalContext.current
    var filename by remember(packageUri) { mutableStateOf<String?>(null) }
    var busy by remember(packageUri) { mutableStateOf(true) }
    var failure by remember(packageUri) { mutableStateOf<String?>(null) }
    var searchRevision by remember { mutableIntStateOf(0) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { video ->
        if (video != null) { PackageVideos.rememberPermission(context, video); open(video) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        if (folder != null) { PackageVideos.rememberPermission(context, folder); searchRevision++ }
    }
    LaunchedEffect(packageUri, searchRevision) {
        busy = true
        try {
            filename = withContext(Dispatchers.IO) { StudyPackage.readPackage(context, packageUri).videoFilename }
            val found = PackageVideos.find(context, filename.orEmpty(), currentVideo)
            if (found != null) open(found)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) { failure = error.message ?: "No se puede leer el paquete." }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Importar paquete PC") }, text = {
        Column {
            if (busy) { CircularProgressIndicator(); Text("Buscando el vídeo del paquete…") }
            else if (failure != null) Text(failure!!)
            else {
                Text("Este paquete corresponde a:\n${filename.orEmpty()}")
                Text("Dale acceso a la carpeta de tus vídeos para encontrar este y los próximos episodios automáticamente, o selecciona este vídeo.")
                TextButton(onClick = { folderPicker.launch(null) }) { Text("Buscar en una carpeta") }
                TextButton(onClick = { videoPicker.launch(arrayOf("video/*")) }) { Text("Seleccionar vídeo") }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Cancelar") } })
}
