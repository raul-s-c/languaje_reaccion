package com.raulsc.lenguareaccion

import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PackageVideoTest {
    private val authority = "com.raulsc.lenguareaccion.test.videos"
    @Test fun packageFindsItsEpisodeInGrantedFolderInsteadOfCurrentVideo() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        instrumentation.context.grantUriPermission(context.packageName, tree, flags)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val a = DocumentsContract.buildDocumentUriUsingTree(tree, "a")
            val b = DocumentsContract.buildDocumentUriUsingTree(tree, "b")
            assertEquals(b, PackageVideos.find(context, "episode-b.mkv", a))
            assertNull(PackageVideos.find(context, "missing.mkv", a))
            assertNull(PackageVideos.find(context, "", a))
        } finally {
            context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            instrumentation.context.revokeUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Test fun startupDoesNotWaitForCloudVideoToDownload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("video", 0)
        val previous = prefs.getString("uri", null)
        prefs.edit().putString("uri", "content://$authority/document/slow").commit()
        try {
            val start = System.nanoTime()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> assertTrue(activity.window.decorView.isShown) }
                val elapsed = (System.nanoTime() - start) / 1_000_000
                assertTrue("Startup waited ${elapsed}ms for cloud provider", elapsed < 10_000)
            }
        } finally { prefs.edit().putString("uri", previous).commit() }
    }
}

