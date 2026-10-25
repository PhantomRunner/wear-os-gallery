package com.pixelwatchgallery.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation fixtures only: these tests never modify the transfer journal or contact a watch. */
@RunWith(AndroidJUnit4::class)
class GalleryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var retries = mutableListOf<String>()
    private var removals = mutableListOf<String>()
    private var sends = 0
    private var adds = 0

    @Before fun configureSystemBars() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        }
    }

    private fun photo(id: String, state: TransferState = TransferState.IDLE, uri: String = "file:///missing.jpg") =
        TransferPhoto(id, uri, "$id.jpg", state, detail = "Watch storage is full. Free some space, then retry.", sizeBytes = 2_400_000)

    private fun show(state: PhoneGalleryState) {
        compose.setContent {
            GalleryTheme { GalleryScreen(state, { adds++ }, { sends++ }, { retries += it }, { removals += it }) }
        }
    }

    @Test fun firstLaunchAndEmptyHistoryHaveClearActions() {
        show(PhoneGalleryState())
        compose.onNodeWithText("A little closer.\nOn your wrist.").assertIsDisplayed()
        compose.onNodeWithTag("empty-add").performScrollTo().performClick()
        assertEquals(1, adds)
        compose.onNodeWithTag("send-button").assertDoesNotExist()
        compose.onNodeWithTag("tab-sent").performClick()
        compose.onNodeWithText("Good things take a moment").assertIsDisplayed()
        compose.onNodeWithText("Once your watch confirms a photo, its delivery will appear here.").performScrollTo().assertIsDisplayed()
    }

    @Test fun tabsSeparateReceiptsAndRestoreSelectedTab() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GalleryTheme {
            GalleryScreen(PhoneGalleryState(listOf(photo("pending"), photo("delivered", TransferState.CONFIRMED))), {}, {}, {}, {})
        } }
        compose.onNodeWithText("Selected · 1").assertIsDisplayed()
        compose.onNodeWithText("Sent · 1").assertIsDisplayed()
        compose.onNodeWithTag("photo-pending").assertIsDisplayed()
        compose.onNodeWithTag("photo-delivered").assertDoesNotExist()
        compose.onNodeWithTag("tab-sent").performClick()
        compose.onNodeWithTag("photo-delivered").assertIsDisplayed()
        compose.onNodeWithTag("send-button").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tab-sent").assertIsSelected()
        compose.onNodeWithTag("photo-delivered").assertIsDisplayed()
        compose.onNodeWithText("Delivery history. Your watch gallery may have changed.").assertIsDisplayed()
    }

    @Test fun onlyReadyPhotosAreSentAndHistoryDoesNotEnableSend() {
        show(PhoneGalleryState(listOf(photo("ready"), photo("received", TransferState.CONFIRMED))))
        compose.onNodeWithText("Send to Watch · 1").assertIsEnabled().performClick()
        assertEquals(1, sends)
    }

    @Test fun failureCanRetryAndDetailsCanRemoveSelection() {
        show(PhoneGalleryState(listOf(photo("failed", TransferState.FAILED))))
        compose.onNodeWithTag("retry-failed").performClick()
        assertEquals(listOf("failed"), retries)
        compose.onNodeWithContentDescription("Details for failed.jpg").performClick()
        compose.onNodeWithText("Watch storage is full. Free some space, then retry.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail-remove").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("failed"), removals)
    }

    @Test fun queuedPhotoIsNotAReceiptAndOnlyConfirmationCanBeRetried() {
        val queued = photo("queued", TransferState.QUEUED)
        val progress = TransferBatch("batch", listOf(BatchPhoto(queued.id, queued.sizeBytes, queued.state))).progress
        show(PhoneGalleryState(listOf(queued), progress))
        compose.onNodeWithText("0 of 1 received").assertIsDisplayed()
        compose.onNodeWithText("0% confirmed").assertIsDisplayed()
        compose.onNodeWithTag("send-button").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Details for queued.jpg").performScrollTo().performClick()
        compose.onNodeWithTag("detail-remove").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("detail-retry").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("queued"), retries)
    }

    @Test fun busyBlocksAddingSendingRetryAndRemoval() {
        show(PhoneGalleryState(listOf(photo("failed", TransferState.FAILED)), busy = true))
        compose.onNodeWithTag("add-photos").assertIsNotEnabled()
        compose.onNodeWithTag("send-button").assertIsNotEnabled()
        compose.onNodeWithTag("retry-failed").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Details for failed.jpg").performClick()
        compose.onNodeWithTag("detail-retry").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("detail-remove").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unknownSizeHasNoInventedPercentageAndPreviewFailureIsHandled() {
        val preparing = photo("unknown", TransferState.PREPARING).copy(sizeBytes = null)
        val progress = TransferBatch("batch", listOf(BatchPhoto(preparing.id, null, preparing.state))).progress
        show(PhoneGalleryState(listOf(preparing), progress, busy = true))
        compose.onNodeWithTag("transfer-indeterminate").assertIsDisplayed()
        compose.onNodeWithText("Size unknown").performScrollTo().assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Preview unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Preview unavailable").onFirst().assertIsDisplayed()
    }

    @Test fun receiptUpdatesAnOpenSheetAndMovesPhotoToSent() {
        val value = mutableStateOf(PhoneGalleryState(listOf(photo("changing", TransferState.QUEUED))))
        compose.setContent { GalleryTheme { GalleryScreen(value.value, {}, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Details for changing.jpg").performClick()
        compose.onNodeWithTag("detail-remove").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { value.value = PhoneGalleryState(listOf(photo("changing", TransferState.CONFIRMED))) }
        compose.onNodeWithTag("detail-remove").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("Close details").performScrollTo().performClick()
        compose.onNodeWithText("You’re all caught up").assertIsDisplayed()
        compose.onNodeWithTag("tab-sent").performClick()
        compose.onNodeWithTag("photo-changing").assertIsDisplayed()
    }

    @Test fun partialFailureAndCompletionRemainVisibleOnBothTabs() {
        val first = photo("received", TransferState.CONFIRMED)
        val second = photo("failed", TransferState.FAILED)
        fun state(photos: List<TransferPhoto>) = PhoneGalleryState(photos,
            TransferBatch("batch", photos.map { BatchPhoto(it.id, it.sizeBytes, it.state) }).progress)
        val value = mutableStateOf(state(listOf(first, second)))
        compose.setContent { GalleryTheme { GalleryScreen(value.value, {}, {}, {}, {}) } }
        compose.onNodeWithText("1 of 2 received").assertIsDisplayed()
        compose.onNodeWithText("50% confirmed").assertIsDisplayed()
        compose.onNodeWithText("1 photo couldn’t be sent. Retry from Selected.").assertIsDisplayed()
        compose.onNodeWithTag("tab-sent").performClick()
        compose.onNodeWithTag("transfer-summary").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show transfer details").performClick()
        compose.onNodeWithText("Progress updates when your watch confirms receipt.").assertIsDisplayed()
        compose.runOnIdle { value.value = state(listOf(first, second.copy(state = TransferState.CONFIRMED))) }
        compose.onNodeWithText("Delivered to your watch").assertIsDisplayed()
        compose.onNodeWithText("2 of 2 received").assertIsDisplayed()
        compose.onNodeWithText("100% confirmed").assertIsDisplayed()
        compose.onNodeWithText("1 photo couldn’t be sent. Retry from Selected.").assertDoesNotExist()
    }

    @Test fun capturesRepresentativeStates() {
        val samples = listOf(
            photo("Alpine morning", uri = landscape("alpine", 0)),
            photo("Coastal light", uri = landscape("coast", 1)),
            photo("Quiet dunes", TransferState.FAILED, landscape("dunes", 2)),
            photo("Into the blue", TransferState.QUEUED, landscape("blue", 3)),
            photo("Golden hour", TransferState.CONFIRMED, landscape("golden", 2)),
            photo("Still waters", TransferState.CONFIRMED, landscape("water", 0))
        )
        val state = mutableStateOf(PhoneGalleryState(samples))
        compose.setContent { GalleryTheme { GalleryScreen(state.value, {}, {}, {}, {}) } }
        compose.waitUntil(10_000) { File(context.cacheDir, "alpine.png").exists() }
        compose.waitForIdle()
        capture("selected")
        compose.onNodeWithTag("photo-grid").performScrollToIndex(3)
        compose.onNodeWithContentDescription("Details for Quiet dunes.jpg").performScrollTo().performClick()
        compose.onNodeWithText("Watch storage is full. Free some space, then retry.").performScrollTo().assertIsDisplayed()
        capture("details")
        compose.onNodeWithContentDescription("Close details").performScrollTo().performClick()
        compose.onNodeWithTag("photo-grid").performScrollToIndex(0)
        compose.onNodeWithTag("tab-sent").performClick()
        compose.waitForIdle()
        capture("sent")
        compose.onNodeWithTag("tab-selected").performClick()
        compose.runOnIdle {
            val entries = samples.take(4).mapIndexed { index, p ->
                BatchPhoto(p.id, p.sizeBytes, if (index == 0) TransferState.CONFIRMED else TransferState.QUEUED)
            }
            state.value = PhoneGalleryState(samples.map { p ->
                entries.firstOrNull { it.id == p.id }?.let { p.copy(state = it.state) } ?: p
            }, TransferBatch("sample", entries).progress)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("transfer-summary").assertIsDisplayed()
        capture("transfer")
        compose.runOnIdle { state.value = PhoneGalleryState() }
        compose.waitForIdle()
        capture("empty")
    }

    private fun capture(name: String) {
        // Wait for Coil's asynchronously loaded previews before saving evidence.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(400)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun landscape(name: String, style: Int): String {
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val skies = arrayOf(intArrayOf(0xFF719BAD.toInt(), 0xFFDDDCD0.toInt()),
            intArrayOf(0xFF7AADA8.toInt(), 0xFFD2D8C4.toInt()),
            intArrayOf(0xFFB59379.toInt(), 0xFFF1D2A0.toInt()),
            intArrayOf(0xFF3F637A.toInt(), 0xFFADC7C6.toInt()))
        paint.shader = LinearGradient(0f, 0f, 0f, 640f, skies[style], null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 640f, 640f, paint)
        paint.shader = null
        paint.color = 0x55FFF1CF
        canvas.drawCircle(460f, 155f, 54f, paint)
        val colors = if (style == 2) intArrayOf(0xFFC0986A.toInt(), 0xFF987248.toInt(), 0xFF604B39.toInt())
            else intArrayOf(0xFF72918C.toInt(), 0xFF3E6667.toInt(), 0xFF25494D.toInt())
        colors.forEachIndexed { index, color ->
            paint.color = color
            val path = Path().apply {
                moveTo(-50f, 430f + index * 95)
                cubicTo(130f, 300f + index * 80, 160f, 130f + index * 170, 315f, 355f + index * 90)
                cubicTo(470f, 510f + index * 20, 560f, 200f + index * 130, 700f, 390f + index * 100)
                lineTo(700f, 700f); lineTo(-50f, 700f); close()
            }
            canvas.drawPath(path, paint)
        }
        val file = File(context.cacheDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.toURI().toString()
    }
}
