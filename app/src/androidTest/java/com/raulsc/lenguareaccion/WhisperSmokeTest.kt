package com.raulsc.lenguareaccion

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WhisperSmokeTest {
    @Test
    fun loadsModelAndRunsArm64Inference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = File(context.noBackupFilesDir, "whisper-models/ggml-small-q5_1.bin")
        assertTrue("El modelo de prueba no está instalado", model.isFile)
        val whisper = WhisperContext.createContextFromFile(model.absolutePath)
        try {
            val segments = whisper.transcribeSegments(FloatArray(16_000 * 3))
            assertNotNull(segments)
        } finally {
            whisper.release()
        }
    }
}
