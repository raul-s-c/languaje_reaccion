package com.raulsc.lenguareaccion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Procesamiento de vídeos",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mantiene activas la transcripción y la traducción"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Procesando vídeo japonés"
        startForeground(NOTIFICATION_ID, notification(label))
        if (intent?.action == ACTION_SELF_TEST) {
            scope.launch {
                val model = runCatching {
                    WhisperModel.valueOf(
                        intent.getStringExtra(EXTRA_MODEL) ?: WhisperModel.TINY_Q5_1.name,
                    )
                }.getOrDefault(WhisperModel.TINY_Q5_1)
                val result = runCatching { runWhisperSelfTest(applicationContext, model) }
                    .fold(
                        onSuccess = { it },
                        onFailure = { "ERROR ${it.stackTraceToString()}" },
                    )
                File(filesDir, "whisper-self-test.txt").writeText(result)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(label: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Lengua Reacción")
        .setContentText(label)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .build()

    companion object {
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 2401
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_MODEL = "model"
        private const val ACTION_SELF_TEST = "com.raulsc.lenguareaccion.SELF_TEST"

        fun start(context: Context, label: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProcessingService::class.java).putExtra(EXTRA_LABEL, label),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProcessingService::class.java))
        }

        fun startSelfTest(context: Context, model: WhisperModel) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProcessingService::class.java)
                    .setAction(ACTION_SELF_TEST)
                    .putExtra(EXTRA_LABEL, "Verificando Whisper ${model.label}")
                    .putExtra(EXTRA_MODEL, model.name),
            )
        }
    }
}
