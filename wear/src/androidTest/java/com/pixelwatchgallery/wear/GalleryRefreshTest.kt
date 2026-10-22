package com.pixelwatchgallery.wear

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
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
@OptIn(ExperimentalTestApi::class)
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

    @Test fun doubleTapZoomsAndResetsWithoutChangingPhoto() {
        openTestPhotos(1)
        compose.onNodeWithTag("zoomable-photo").performTouchInput { doubleClick(center) }
        assertZoom("Zoom 250%")
        rotateCrown(1f)
        assertZoom("Zoom 250%")
        compose.onNodeWithTag("zoomable-photo").performTouchInput { doubleClick(center) }
        assertZoom("Zoom 100%")
        waitForText("1 / 1")
    }

    @Test fun pinchAndPanKeepCurrentPhotoAndEdgeBackStillWorks() {
        openTestPhotos(2)
        compose.onNodeWithTag("zoomable-photo").performTouchInput {
            pinch(start0 = Offset(width * 0.35f, centerY), end0 = Offset(width * 0.15f, centerY),
                start1 = Offset(width * 0.65f, centerY), end1 = Offset(width * 0.85f, centerY), durationMillis = 600)
        }
        compose.onNodeWithTag("zoomable-photo").assert(SemanticsMatcher("Photo is zoomed") {
            val description = it.config.getOrNull(SemanticsProperties.StateDescription)
            description != null && description != "Zoom 100%"
        })
        compose.onNodeWithTag("zoomable-photo").performTouchInput {
            swipe(Offset(width * 0.7f, centerY), Offset(width * 0.3f, centerY))
        }
        waitForText("1 / 2")
        compose.onNodeWithTag("zoomable-photo").performTouchInput {
            swipe(Offset(2f, centerY), Offset(width * 0.9f, centerY))
        }
        waitForText("2 photos")
    }

    @Test fun crownLoopsBothWaysAndResetsZoomOnNewPhoto() {
        openTestPhotos(3)
        compose.onNodeWithTag("zoomable-photo").performTouchInput { doubleClick(center) }
        assertZoom("Zoom 250%")
        rotateCrown(1f)
        waitForText("2 / 3")
        assertZoom("Zoom 100%")
        rotateCrown(1f)
        waitForText("3 / 3")
        rotateCrown(1f)
        waitForText("1 / 3")
        rotateCrown(-1f)
        waitForText("3 / 3")
    }

    @Test fun normalSwipeAndGridCrownScrollingRemainAvailable() {
        openTestPhotos(8)
        compose.onNodeWithTag("zoomable-photo").performTouchInput { swipeLeft() }
        waitForText("2 / 8")
        rotateCrown(1f)
        waitForText("3 / 8")
        compose.onNodeWithTag("zoomable-photo").performTouchInput {
            swipe(Offset(2f, centerY), Offset(width * 0.9f, centerY))
        }
        waitForText("8 photos")
        compose.onNodeWithTag("gallery-grid").performRotaryScrollInput { rotateToScrollVertically(300f) }
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("gallery-grid").fetchSemanticsNode().config[
                SemanticsProperties.VerticalScrollAxisRange].value() > 0f
        }
    }

    private fun openTestPhotos(count: Int) {
        compose.waitUntil(10_000) { repository.state.value.loaded }
        check(repository.state.value.photos.isEmpty()) { "Interaction tests require a disposable emulator with no existing photos." }
        repeat(count) { receivePhoto() }
        waitForText(photoCount(count))
        compose.onAllNodesWithContentDescription("Saved photo").onFirst().performClick()
        waitForText("1 / $count")
    }

    private fun rotateCrown(direction: Float) {
        val distance = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density * 32f * 1.1f
        compose.onNodeWithTag("photo-viewer").performRotaryScrollInput { rotateToScrollVertically(direction * distance) }
    }

    private fun assertZoom(description: String) {
        compose.onNodeWithTag("zoomable-photo").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
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
