package com.pixelwatchgallery.wear

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchDeletionTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(base.cacheDir, "delete-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }
    private val repository = PhotoRepository(context)
    @After fun cleanup() { directory.deleteRecursively() }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }

    @Test fun partialFailurePublishesActualFilesAndRetainsTombstones() = runBlocking {
        val good = UUID.randomUUID().toString()
        val bad = UUID.randomUUID().toString()
        val remaining = UUID.randomUUID().toString()
        for (id in listOf(good, bad, remaining)) png().inputStream().use { repository.receive(id, "image/png", it) }
        // Simulate an undeletable matching path without relying on chmod/root privileges.
        val obstacle = File(directory, "photos/$bad.jpg").apply { mkdirs() }
        File(obstacle, "block").writeText("test")
        val counts = mutableListOf<Int>()
        val result = repository.deleteMany(linkedSetOf(good, bad)) { counts += it }
        assertEquals(setOf(good), result.deleted)
        assertEquals(setOf(bad), result.failures.keys)
        assertEquals(listOf(1, 2), counts)
        assertEquals(setOf(bad, remaining), repository.state.value.photos.map { it.nameWithoutExtension }.toSet())
        assertTrue(repository.received(good))
        png().inputStream().use { repository.receive(good, "image/png", it) }
        assertEquals(2, repository.state.value.photos.size)
        obstacle.deleteRecursively()
        assertTrue(repository.deleteMany(setOf(bad)).failures.isEmpty())
        val reopened = PhotoRepository(context)
        reopened.refresh()
        assertEquals(remaining, reopened.state.value.photos.single().nameWithoutExtension)
    }

    @Test fun deletingEverythingAndMissingIdLeavesPersistentEmptyGallery() = runBlocking {
        val id = UUID.randomUUID().toString()
        png().inputStream().use { repository.receive(id, "image/png", it) }
        val result = repository.deleteMany(setOf(id, UUID.randomUUID().toString()))
        assertTrue(result.failures.isEmpty())
        assertTrue(repository.state.value.photos.isEmpty())
        val reopened = PhotoRepository(context)
        reopened.refresh()
        assertTrue(reopened.state.value.photos.isEmpty())
        assertTrue(reopened.received(id))
    }
}
