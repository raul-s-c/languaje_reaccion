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

class StudyPackageTest {
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
