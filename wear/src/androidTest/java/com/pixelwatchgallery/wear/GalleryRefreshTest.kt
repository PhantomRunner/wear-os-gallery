package com.pixelwatchgallery.wear

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a disposable emulator. Only UUIDs created by this test are deleted. */
@RunWith(AndroidJUnit4::class)
class GalleryRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val created = mutableListOf<String>()
    private val repository get() = PhotoRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)

    @After fun removeOnlyTestPhotos() = runBlocking {
        created.forEach { repository.delete(it) }
    }

    @Test fun incomingPhotoAndDeletionUpdateAlreadyOpenGallery() {
        compose.waitUntil(10_000) { repository.state.value.loaded }
        val initialCount = repository.state.value.photos.size
        val id = receivePhoto()
        waitForText(photoCount(initialCount + 1))
        compose.onNodeWithText(photoCount(initialCount + 1)).assertIsDisplayed()

        runBlocking { repository.delete(id) }
        created.remove(id)
        waitForText(photoCount(initialCount))
    }

    @Test fun openViewerObservesIncomingPhotosAndDeletion() {
        compose.waitUntil(10_000) { repository.state.value.loaded }
        val initialCount = repository.state.value.photos.size
        val first = receivePhoto()
        waitForText(photoCount(initialCount + 1))
        compose.onAllNodesWithContentDescription("Saved photo").onFirst().performClick()
        waitForText(" / ${initialCount + 1}", substring = true)

        receivePhoto()
        waitForText(" / ${initialCount + 2}", substring = true)
        runBlocking { repository.delete(first) }
        created.remove(first)
        waitForText(" / ${initialCount + 1}", substring = true)
    }

    private fun receivePhoto(): String {
        val id = UUID.randomUUID().toString()
        created += id
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(android.graphics.Color.CYAN)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        runBlocking { bytes.inputStream().use { repository.receive(id, "image/png", it) } }
        return id
    }

    private fun photoCount(count: Int) = when (count) {
        0 -> "No photos yet"
        1 -> "1 photo"
        else -> "$count photos"
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
