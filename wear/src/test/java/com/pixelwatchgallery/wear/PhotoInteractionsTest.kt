package com.pixelwatchgallery.wear

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class PhotoInteractionsTest {
    @Test fun crownWrapsBothEndsAndHandlesFastTurns() {
        assertEquals(0, wrappedPhotoIndex(2, 1, 3))
        assertEquals(2, wrappedPhotoIndex(0, -1, 3))
        assertEquals(1, wrappedPhotoIndex(0, 10, 3))
        assertEquals(2, wrappedPhotoIndex(0, -10, 3))
    }

    @Test fun emptyAndSinglePhotoCollectionsStaySafe() {
        assertEquals(0, wrappedPhotoIndex(0, 100, 0))
        assertEquals(0, wrappedPhotoIndex(0, -100, 1))
    }

    @Test fun smallCrownEventsAccumulateInsteadOfSkippingPhotos() {
        val crown = CrownPhotoStepper(32f)
        assertEquals(0, crown.steps(12f, 0))
        assertEquals(0, crown.steps(12f, 20))
        assertEquals(1, crown.steps(12f, 40))
        assertEquals(2, crown.steps(64f, 60))
    }

    @Test fun crownReversalAndPauseDiscardOldRemainder() {
        val crown = CrownPhotoStepper(32f)
        assertEquals(0, crown.steps(30f, 0))
        assertEquals(-1, crown.steps(-32f, 20))
        assertEquals(0, crown.steps(30f, 40))
        assertEquals(0, crown.steps(3f, 500))
        assertEquals(0, crown.steps(Float.NaN, 520))
        assertEquals(0, crown.steps(Float.POSITIVE_INFINITY, 530))
    }

    @Test fun zoomIsBoundedAndResetReturnsToFit() {
        val zoom = PhotoZoomState()
        zoom.updateViewport(IntSize(384, 384))
        zoom.updateImage(Size(1024f, 1024f))
        zoom.transform(100f, Offset(10000f, -10000f))
        assertEquals(3f, zoom.scale, 0f)
        assertEquals(Offset(384f, -384f), zoom.offset)
        zoom.transform(0.001f, Offset(100f, 100f))
        assertEquals(1f, zoom.scale, 0f)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test fun landscapePanDoesNotMoveShortImageOffScreen() {
        val offset = boundedPhotoPan(Offset(10000f, 10000f), 3f,
            IntSize(384, 384), Size(1600f, 400f))
        assertEquals(384f, offset.x, 0f)
        assertEquals(0f, offset.y, 0f)
    }

    @Test fun portraitPanHasOnlyVerticalRange() {
        val offset = boundedPhotoPan(Offset(-1000f, -1000f), 3f,
            IntSize(384, 384), Size(400f, 1600f))
        assertEquals(0f, offset.x, 0.001f)
        assertEquals(-384f, offset.y, 0.001f)
    }

    @Test fun doubleTapZoomsAtTapThenResets() {
        val zoom = PhotoZoomState()
        zoom.updateViewport(IntSize(384, 384))
        zoom.updateImage(Size(768f, 768f))
        zoom.toggle(Offset(100f, 100f))
        assertEquals(2.5f, zoom.scale, 0f)
        assertTrue(zoom.offset.x > 0f && zoom.offset.y > 0f)
        zoom.toggle()
        assertEquals(1f, zoom.scale, 0f)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test fun invalidGesturesCannotCorruptZoom() {
        val zoom = PhotoZoomState()
        zoom.transform(Float.NaN, Offset.Zero)
        zoom.transform(2f, Offset(Float.POSITIVE_INFINITY, 0f))
        assertEquals(1f, zoom.scale, 0f)
        assertEquals(Offset.Zero, zoom.offset)
    }
}
