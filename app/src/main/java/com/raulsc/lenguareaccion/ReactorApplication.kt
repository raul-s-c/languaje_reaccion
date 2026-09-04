package com.raulsc.lenguareaccion

import android.app.Application
import android.content.Context
import android.os.Process
import java.io.File
import java.time.Instant

class ReactorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile(context).writeText(
                    buildString {
                        appendLine("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                        appendLine("Fecha: ${Instant.now()}")
                        appendLine("Hilo: ${thread.name}")
                        appendLine("Versión: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine()
                        append(error.stackTraceToString())
                    },
                )
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    fun read(context: Context): String? = runCatching {
        crashFile(context).takeIf(File::exists)?.readText()?.take(4_000)
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    private fun crashFile(context: Context) = File(context.filesDir, FILE_NAME)
}
