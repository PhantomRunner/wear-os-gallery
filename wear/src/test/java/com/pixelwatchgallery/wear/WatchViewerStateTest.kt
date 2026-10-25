package com.pixelwatchgallery.wear

import org.junit.Assert.*
import org.junit.Test

class WatchViewerStateTest {
    @Test fun deleteOnlyAppearsAfterATapAndSecondTapHidesBoth() {
        val controls = ViewerControls()
        assertTrue(controls.counterVisible)
        assertFalse(controls.actionsVisible)
        controls.tap()
        assertTrue(controls.counterVisible)
        assertTrue(controls.actionsVisible)
        controls.tap()
        assertFalse(controls.counterVisible)
        assertFalse(controls.actionsVisible)
    }

    @Test fun expiryAndReopeningNeverExposeDelete() {
        val controls = ViewerControls()
        controls.tap()
        controls.expire()
        assertFalse(controls.actionsVisible)
        assertFalse(controls.counterVisible)
        controls.opened()
        assertTrue(controls.counterVisible)
        assertFalse(controls.actionsVisible)
    }

    @Test fun feedbackOnlyForChangedPhotosAndAtMostEvery50Milliseconds() {
        val gate = CrownFeedbackGate()
        assertFalse(gate.request(null, "a", 0))
        assertFalse(gate.request("a", "a", 0))
        assertTrue(gate.request("a", "b", 0))
        assertFalse(gate.request("b", "c", 49))
        assertTrue(gate.request("c", "a", 50))
        assertFalse(gate.request("a", null, 200))
        assertTrue(gate.request("a", "b", 200))
    }

    @Test fun viewerRestoresByIdAfterNewPhotosArrive() {
        val position = WatchPosition(GalleryDestination.VIEWER, "selected")
        assertEquals("selected", position.resumePhoto(listOf("new", "selected", "old")))
        assertNull(position.resumePhoto(listOf("new", "old")))
        assertNull(position.copy(destination = GalleryDestination.GALLERY).resumePhoto(listOf("selected")))
    }

    @Test fun gridAnchorAccountsForHeaderAndInsertion() {
        val position = WatchPosition(gridAnchorId = "anchor", gridOffset = 37)
        assertEquals(2, position.gridIndex(listOf("a", "anchor", "b")))
        assertEquals(3, position.gridIndex(listOf("new", "a", "anchor", "b")))
        assertEquals(37, position.gridOffset(listOf("anchor")))
    }

    @Test fun missingAnchorAndEmptyGalleryRestoreToTop() {
        val position = WatchPosition(gridAnchorId = "gone", gridOffset = 37)
        assertEquals(0, position.gridIndex(listOf("a")))
        assertEquals(0, position.gridOffset(listOf("a")))
        assertEquals(0, position.gridIndex(emptyList()))
        assertEquals(0, WatchPosition(gridOffset = -5).gridOffset(emptyList()))
    }
}
