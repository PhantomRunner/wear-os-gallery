package com.pixelwatchgallery.wear

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.MaterialTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionGalleryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun partialFailureKeepsOnlyFailedPhotosSelectedAndRetryUsesThoseIds() {
        val photos = mutableStateOf(listOf(File("/test/a.png"), File("/test/b.png")))
        val deletion = mutableStateOf(BatchDeletion())
        val requests = mutableListOf<Set<String>>()
        compose.setContent { MaterialTheme {
            SelectionGallery(photos.value, WatchPosition(), deletion.value, { _, _ -> },
                onDelete = { requests += it }, onResultHandled = { deletion.value = BatchDeletion() }, onDone = {})
        } }
        compose.onNodeWithTag("select-all").performScrollTo().performClick()
        compose.onNodeWithTag("delete-selected").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-delete-selected").performScrollTo().performClick()
        assertEquals(setOf("a", "b"), requests.single())
        compose.runOnIdle {
            photos.value = listOf(File("/test/b.png"), File("/test/new.png"))
            deletion.value = BatchDeletion(setOf("a", "b"), 2, result = BatchDeletionResult(setOf("a"), mapOf("b" to "disk error")))
        }
        compose.onNodeWithTag("batch-delete-error").assertExists()
        compose.onNodeWithTag("gallery-grid").performScrollToKey("b.png")
        compose.onNodeWithTag("grid-photo-b").assertIsOn()
        compose.onNodeWithTag("grid-photo-new").assertIsOff()
        compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
        compose.onNodeWithTag("delete-selected").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-delete-selected").performScrollTo().performClick()
        assertEquals(setOf("b"), requests.last())
    }

    @Test fun inFlightDeletionShowsProgressAndHasNoClickableConfirmation() {
        val state = mutableStateOf(BatchDeletion())
        var requests = 0
        compose.setContent { MaterialTheme {
            SelectionGallery(listOf(File("/test/a.png")), WatchPosition(), state.value, { _, _ -> },
                onDelete = { requests++; state.value = BatchDeletion(it, running = true) }, onResultHandled = {}, onDone = {})
        } }
        compose.onNodeWithTag("select-all").performScrollTo().performClick()
        compose.onNodeWithTag("delete-selected").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-delete-selected").performScrollTo().performClick()
        compose.onNodeWithTag("deleting-photos").assertTextEquals("Deleting 0 of 1…")
        compose.onNodeWithTag("confirm-delete-selected").assertDoesNotExist()
        compose.onNodeWithTag("cancel-delete-selected").assertDoesNotExist()
        assertEquals(1, requests)
    }
}
