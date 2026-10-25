package com.pixelwatchgallery.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Path
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        if (InstrumentationRegistry.getArguments().getString("preserve-fixture") != "true") {
            created.forEach { repository.delete(it) }
        }
        WatchPositionStore.get(InstrumentationRegistry.getInstrumentation().targetContext).awaitSaved()
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

    @Test fun deleteRequiresSingleTapAndNeverAppearsForZoomOrPaging() {
        openTestPhotos(3)
        compose.onNodeWithTag("delete-photo").assertDoesNotExist()
        compose.onNodeWithTag("zoomable-photo").performTouchInput { doubleClick(center) }
        assertZoom("Zoom 250%")
        compose.onNodeWithTag("delete-photo").assertDoesNotExist()
        rotateCrown(1f)
        waitForText("2 / 3")
        compose.onNodeWithTag("delete-photo").assertDoesNotExist()
        compose.onNodeWithTag("zoomable-photo").performTouchInput { click(center) }
        waitForDelete()
        compose.onNodeWithTag("zoomable-photo").performTouchInput { click(center) }
        // Single-tap waits out the double-tap window, then the 150 ms fade finishes.
        compose.waitUntil(1_500) { compose.onAllNodesWithTag("delete-photo").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("delete-photo").assertDoesNotExist()
        compose.onNodeWithTag("zoomable-photo").performTouchInput { swipeLeft() }
        waitForText("3 / 3")
        compose.onNodeWithTag("delete-photo").assertDoesNotExist()
    }

    @Test fun expiredControlsDoNotLeaveAnInvisibleDeleteTarget() {
        openTestPhotos(1)
        compose.mainClock.advanceTimeBy(3_400)
        compose.onNodeWithText("1 / 1").assertDoesNotExist()
        compose.onNodeWithTag("delete-photo").assertDoesNotExist()
        compose.onNodeWithTag("zoomable-photo").performTouchInput { click(Offset(centerX, height * 0.85f)) }
        waitForDelete()
        compose.onNodeWithText("Delete photo?").assertDoesNotExist()
        compose.onNodeWithTag("delete-photo").performClick()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Delete photo?").assertIsDisplayed()
        compose.onNodeWithTag("cancel-delete").performClick()
        waitForText("1 / 1")
        compose.mainClock.advanceTimeBy(3_400)
        compose.onNodeWithTag("delete-photo").assertDoesNotExist()
    }

    @Test fun deleteConfirmationRemovesOnlyTheSelectedWatchPhoto() {
        openTestPhotos(3)
        val removed = repository.state.value.photos.first().nameWithoutExtension
        compose.onNodeWithTag("zoomable-photo").performTouchInput { click(center) }
        waitForDelete()
        compose.onNodeWithTag("delete-photo").performClick()
        compose.onNodeWithTag("confirm-delete").performClick()
        compose.waitUntil(10_000) { repository.state.value.photos.size == 2 }
        check(repository.state.value.photos.none { it.nameWithoutExtension == removed })
        waitForText("1 / 2")
        compose.onNodeWithTag("delete-photo").assertDoesNotExist()
    }

    @Test fun actualFingerHoldPausesExpiryWithoutBecomingATap() {
        openTestPhotos(1)
        compose.onNodeWithTag("zoomable-photo").performTouchInput { click(center) }
        waitForDelete()
        compose.onNodeWithTag("zoomable-photo").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithTag("delete-photo").assertIsDisplayed()
        compose.onNodeWithTag("zoomable-photo").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("delete-photo").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_400)
        compose.onNodeWithTag("delete-photo").assertDoesNotExist()
    }

    @Test fun accessibilityHasPhotoActionsAndSeparateZoomAction() {
        openTestPhotos(1)
        compose.onNodeWithTag("zoomable-photo").performClick()
        waitForDelete()
        val actions = compose.onNodeWithTag("zoomable-photo").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { check(actions.single { it.label == "Toggle photo zoom" }.action()) }
        assertZoom("Zoom 250%")
    }

    @Test fun recreationRestoresCurrentPhotoAtFitAndExplicitBackRestoresGrid() {
        openTestPhotos(3)
        rotateCrown(1f)
        waitForText("2 / 3")
        compose.onNodeWithTag("zoomable-photo").performTouchInput { doubleClick(center) }
        assertZoom("Zoom 250%")
        compose.onNodeWithTag("zoomable-photo").performTouchInput { click(center) }
        waitForDelete()
        val positions = WatchPositionStore.get(compose.activity)
        runBlocking { positions.awaitSaved() }
        val expected = positions.state.value.position.photoId
        receivePhoto()
        waitForText("3 / 4")
        compose.waitUntil { positions.state.value.position.photoId == expected }
        runBlocking { positions.awaitSaved() }
        compose.activityRule.scenario.recreate()
        waitForText("3 / 4")
        assertZoom("Zoom 100%")
        compose.onNodeWithTag("delete-photo").assertDoesNotExist()
        check(positions.state.value.position.photoId == expected)
        compose.onNodeWithTag("zoomable-photo").performTouchInput {
            swipe(Offset(2f, centerY), Offset(width * 0.9f, centerY))
        }
        waitForText("4 photos")
        compose.waitUntil { positions.state.value.position.destination == GalleryDestination.GALLERY }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
        compose.onNodeWithTag("photo-viewer").assertDoesNotExist()
    }

    @Test fun gridRestoresItsPhotoAnchorAfterIncomingImage() {
        openTestPhotos(20)
        compose.onNodeWithTag("zoomable-photo").performTouchInput {
            swipe(Offset(2f, centerY), Offset(width * 0.9f, centerY))
        }
        waitForText("20 photos")
        compose.onNodeWithTag("gallery-grid").performScrollToIndex(11)
        compose.waitForIdle()
        val positions = WatchPositionStore.get(compose.activity)
        compose.waitUntil { positions.state.value.position.gridAnchorId != null }
        val anchor = positions.state.value.position.gridAnchorId!!
        runBlocking { positions.awaitSaved() }
        receivePhoto()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("grid-photo-$anchor").assertIsDisplayed()
        compose.onNodeWithTag("photo-viewer").assertDoesNotExist()
    }

    @Test fun capturesViewerStates() {
        compose.waitUntil(10_000) { repository.state.value.loaded }
        check(repository.state.value.photos.isEmpty()) { "Screenshots require a disposable emulator." }
        repeat(3) { receivePhoto(landscape(it)) }
        waitForText("3 photos")
        compose.onAllNodesWithContentDescription("Saved photo").onFirst().performClick()
        waitForText("1 / 3")
        capture("counter")
        compose.mainClock.advanceTimeBy(3_400)
        capture("clean-photo")
        compose.onNodeWithTag("zoomable-photo").performTouchInput { click(center) }
        waitForDelete()
        capture("photo-actions")
        compose.onNodeWithTag("delete-photo").performClick()
        capture("delete-confirmation")
        compose.onNodeWithTag("cancel-delete").performClick()
        rotateCrown(1f)
        waitForText("2 / 3")
        compose.onNodeWithTag("zoomable-photo").performTouchInput { doubleClick(center) }
        assertZoom("Zoom 250%")
        capture("zoom")
        val positions = WatchPositionStore.get(compose.activity)
        runBlocking { positions.awaitSaved() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "cold-start-fixture.json").writeText(JSONObject().apply {
            put("ids", JSONArray(created))
            put("selected", positions.state.value.position.photoId)
        }.toString())
    }

    private fun waitForDelete() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("delete-photo").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(250)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "wear04-screenshots").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun landscape(style: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val sky = if (style == 1) intArrayOf(0xFF769FAC.toInt(), 0xFFE1DFCF.toInt())
            else if (style == 0) intArrayOf(0xFFAD8B71.toInt(), 0xFFE8CD9E.toInt())
            else intArrayOf(0xFF4E7583.toInt(), 0xFFB7CEBF.toInt())
        paint.shader = LinearGradient(0f, 0f, 0f, 640f, sky, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 640f, 640f, paint)
        paint.shader = null
        paint.color = 0x99FFF1CE.toInt()
        canvas.drawCircle(445f, 170f, 50f, paint)
        val colors = if (style == 0) intArrayOf(0xFFBA9368.toInt(), 0xFF91704E.toInt(), 0xFF604B39.toInt())
            else intArrayOf(0xFF79968E.toInt(), 0xFF456E69.toInt(), 0xFF234C4D.toInt())
        colors.forEachIndexed { index, color ->
            paint.color = color
            canvas.drawPath(Path().apply {
                moveTo(-40f, 420f + index * 80)
                cubicTo(130f, 300f + index * 80, 180f, 160f + index * 140, 325f, 355f + index * 80)
                cubicTo(465f, 490f + index * 20, 560f, 250f + index * 120, 680f, 390f + index * 90)
                lineTo(680f, 680f); lineTo(-40f, 680f); close()
            }, paint)
        }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        return bytes
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

    private fun receivePhoto(content: ByteArray? = null): String {
        val id = UUID.randomUUID().toString()
        created += id
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = content ?: try {
            bitmap.eraseColor(android.graphics.Color.CYAN)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        if (content != null) bitmap.recycle()
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
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("photo-viewer").fetchSemanticsNodes().any {
                    val page = it.config.getOrNull(SemanticsProperties.StateDescription) ?: ""
                    if (substring) page.contains(text) else page == text
                }
        }
    }
}
