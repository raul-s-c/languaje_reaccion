package com.raulsc.lenguareaccion

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class AppUpdater(private val context: Context) {
    companion object {
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/raul-s-c/languaje_reaccion/main/updates/latest.json"
        private const val USER_AGENT = "LenguaReaccion-Android/${BuildConfig.VERSION_NAME}"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun check(callback: (UpdateState) -> Unit) {
        callback(UpdateState.Checking)
        executor.execute {
            try {
                val info = parseManifest(readText(MANIFEST_URL))
                val available = info.versionCode > BuildConfig.VERSION_CODE ||
                    isNewerVersion(info.versionName, BuildConfig.VERSION_NAME)
                post(callback, if (available) UpdateState.Available(info) else UpdateState.UpToDate)
            } catch (error: Exception) {
                post(callback, UpdateState.Failed(error.userMessage("No se pudo comprobar la versión")))
            }
        }
    }

    fun download(info: UpdateInfo, callback: (UpdateState) -> Unit) {
        executor.execute {
            try {
                val directory = prepareUpdateDirectory()
                val target = File(directory, "lengua-reaccion-${info.versionName}.apk")
                downloadFile(info.apkUrl, target) { percent ->
                    post(callback, UpdateState.Downloading(percent))
                }
                val actualHash = sha256(target)
                if (!actualHash.equals(info.sha256, ignoreCase = true)) {
                    target.delete()
                    error("La firma SHA-256 no coincide; descarga descartada")
                }
                post(callback, UpdateState.Ready(info, target))
            } catch (error: Exception) {
                post(callback, UpdateState.Failed(error.userMessage("No se pudo descargar la actualización")))
            }
        }
    }

    fun install(apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return false
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        return true
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun prepareUpdateDirectory(): File {
        clearDirectory(context.cacheDir)
        context.externalCacheDir?.let(::clearDirectory)
        return File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
    }

    private fun clearDirectory(directory: File) {
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun parseManifest(raw: String): UpdateInfo {
        val json = JSONObject(raw)
        return UpdateInfo(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = json.getString("apkUrl"),
            sha256 = json.getString("sha256"),
            notes = json.optString("notes", "Mejoras y correcciones"),
        )
    }

    private fun readText(url: String): String {
        val connection = open(url)
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun downloadFile(url: String, target: File, progress: (Int) -> Unit) {
        val connection = open(url)
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                var lastPercent = -1
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    if (total > 0) {
                        val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            progress(percent)
                        }
                    }
                }
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/octet-stream, application/json")
            val status = connection.responseCode
            if (status in 300..399) {
                current = connection.getHeaderField("Location") ?: error("Redirección sin destino")
                connection.disconnect()
            } else {
                if (status !in 200..299) error("Respuesta HTTP $status")
                return connection
            }
        }
        error("Demasiadas redirecciones")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun post(callback: (UpdateState) -> Unit, state: UpdateState) {
        main.post { callback(state) }
    }

    private fun Exception.userMessage(prefix: String): String =
        "$prefix: ${message ?: javaClass.simpleName}"
}
