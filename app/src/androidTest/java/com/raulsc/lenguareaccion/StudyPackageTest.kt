package com.raulsc.lenguareaccion

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

class StudyPackageTest {
    @Test fun importRejectsWrongEpisodeAndResetsOnlySuccessfulSubtitleOffset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val a = File.createTempFile("import-a", ".mkv", context.cacheDir).apply { writeText("video A") }
        val b = File.createTempFile("import-b", ".mkv", context.cacheDir).apply { writeText("video B") }
        val pack = File.createTempFile("import-package", ".lrpack", context.cacheDir)
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val payload = """{"formatVersion":1,"videoId":"${hash(a.readBytes())}","videoFilename":"${a.name}","durationMillis":1000,"segments":[{"startMillis":0,"endMillis":1000,"japanese":"A","spanish":"Uno"}]}""".toByteArray()
        ZipOutputStream(pack.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("study.json")); zip.write(payload); zip.closeEntry()
            zip.putNextEntry(ZipEntry("study.sha256")); zip.write(hash(payload).toByteArray()); zip.closeEntry()
        }
        val aUri = Uri.fromFile(a)
        val bUri = Uri.fromFile(b)
        val prefs = context.getSharedPreferences("playback_sync", 0)
        prefs.edit().putLong("sub_${playbackKey(aUri.toString())}", 60000).putLong("audio_${playbackKey(aUri.toString())}", 200).commit()
        lateinit var controller: LocalTranscriptionController
        instrumentation.runOnMainSync { controller = LocalTranscriptionController(context) }
        fun waitUntil(predicate: (TranscriptionState) -> Boolean) {
            repeat(200) {
                var finished = false
                instrumentation.runOnMainSync { finished = predicate(controller.state) }
                if (finished) return
                Thread.sleep(20)
            }
            throw AssertionError("Import did not reach expected state")
        }
        try {
            instrumentation.runOnMainSync { controller.selectVideo(bUri); controller.importPackage(Uri.fromFile(pack), bUri) }
            waitUntil { it is TranscriptionState.Failed }
            assertTrue(TranscriptStore(context).load(bUri) == null)
            assertEquals(60000L, prefs.getLong("sub_${playbackKey(aUri.toString())}", 0))
            instrumentation.runOnMainSync { controller.selectVideo(aUri); controller.importPackage(Uri.fromFile(pack), aUri) }
            waitUntil { it is TranscriptionState.Completed }
            assertEquals("A", TranscriptStore(context).load(aUri)!!.segments.single().japanese)
            assertEquals(0L, prefs.getLong("sub_${playbackKey(aUri.toString())}", 0))
            assertEquals(200L, prefs.getLong("audio_${playbackKey(aUri.toString())}", 0))
        } finally {
            instrumentation.runOnMainSync { controller.close() }
            a.delete(); b.delete(); pack.delete()
        }
    }

    @Test fun verifiesVideoBytesAndRejectsAnotherEpisode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val a = File.createTempFile("episode-a", ".mkv", context.cacheDir).apply { writeText("episode A") }
        val b = File.createTempFile("episode-b", ".mkv", context.cacheDir).apply { writeText("episode B") }
        val hash = MessageDigest.getInstance("SHA-256").digest(a.readBytes()).joinToString("") { "%02x".format(it) }
        val study = ImportedStudyPackage(hash, a.name, 1000, listOf(SubtitleSegment(0, 1000, "日本語")))
        try {
            StudyPackage.verifyVideo(context, Uri.fromFile(a), study) {}
            assertTrue(runCatching { StudyPackage.verifyVideo(context, Uri.fromFile(b), study) {} }.isFailure)
        } finally { a.delete(); b.delete() }
    }

    @Test fun storesAndRestoresEachEpisodeIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TranscriptStore(context)
        val a = Uri.parse("content://binding-test/${System.nanoTime()}/A")
        val b = Uri.parse("content://binding-test/${System.nanoTime()}/B")
        store.save(a, WhisperModel.BASE_Q5_1, listOf(SubtitleSegment(100, 200, "A")), "Paquete A")
        assertTrue(store.load(b) == null)
        store.save(b, WhisperModel.BASE_Q5_1, listOf(SubtitleSegment(300, 400, "B")), "Paquete B")
        assertEquals("A", TranscriptStore(context).load(a)!!.segments.single().japanese)
        assertEquals("B", TranscriptStore(context).load(b)!!.segments.single().japanese)
        assertEquals("Paquete A", store.load(a)!!.source)
    }

    @Test fun switchingVideoClearsPreviousCuesAndRestoresOnlyItsOwn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val a = Uri.parse("content://switch-test/${System.nanoTime()}/A")
        val b = Uri.parse("content://switch-test/${System.nanoTime()}/B")
        TranscriptStore(context).save(a, WhisperModel.BASE_Q5_1, listOf(SubtitleSegment(0,1000,"A")))
        lateinit var controller: LocalTranscriptionController
        instrumentation.runOnMainSync { controller = LocalTranscriptionController(context); controller.selectVideo(a) }
        fun waitForA() {
            repeat(100) {
                var ready = false
                instrumentation.runOnMainSync { ready = (controller.state as? TranscriptionState.Completed)?.segments?.singleOrNull()?.japanese == "A" }
                if (ready) return
                Thread.sleep(20)
            }
            throw AssertionError("Episode A was not restored")
        }
        try {
            waitForA()
            instrumentation.runOnMainSync {
                controller.selectVideo(b)
                assertTrue(controller.state !is TranscriptionState.Completed)
            }
            Thread.sleep(100)
            instrumentation.runOnMainSync { assertTrue(controller.state !is TranscriptionState.Completed); controller.selectVideo(a) }
            waitForA()
        } finally { instrumentation.runOnMainSync { controller.close() } }
    }

    @Test fun acceptsValidPackageAndRejectsTampering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("package-test-", ".lrpack", context.cacheDir)
        val payload = """{"formatVersion":1,"segments":[{"startMillis":0,"endMillis":1000,"japanese":"日本語","spanish":"Japonés","reading":"にほんご"}]}""".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        fun write(checksum: String) {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("study.json")); zip.write(payload); zip.closeEntry()
                zip.putNextEntry(ZipEntry("study.sha256")); zip.write(checksum.toByteArray()); zip.closeEntry()
            }
        }
        try {
            write(hash)
            assertEquals("Japonés", StudyPackage.read(context, Uri.fromFile(file)).single().spanish)
            write("invalid")
            assertTrue(runCatching { StudyPackage.read(context, Uri.fromFile(file)) }.isFailure)
        } finally { file.delete() }
    }
}
