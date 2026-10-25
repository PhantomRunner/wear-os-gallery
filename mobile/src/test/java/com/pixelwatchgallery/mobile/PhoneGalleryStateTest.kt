package com.pixelwatchgallery.mobile

import org.junit.Assert.*
import org.junit.Test

class PhoneGalleryStateTest {
    private fun photo(state: TransferState) = TransferPhoto(state.name, "content://photo", "photo.jpg", state)

    @Test fun receiptIsTheOnlyWayIntoSent() {
        val state = PhoneGalleryState(TransferState.entries.map(::photo))
        assertEquals(listOf(TransferState.CONFIRMED), state.sent.map { it.state })
        assertEquals(TransferState.entries.filterNot { it == TransferState.CONFIRMED }, state.selected.map { it.state })
        assertEquals(1, state.readyCount)
    }

    @Test fun emptyAndHistoryOnlyDoNotShowSendBar() {
        assertFalse(PhoneGalleryState().showSendBar)
        assertFalse(PhoneGalleryState(listOf(photo(TransferState.CONFIRMED))).showSendBar)
    }

    @Test fun queuedPhotoKeepsReceiptStatusVisibleWithoutEnablingNewSend() {
        val state = PhoneGalleryState(listOf(photo(TransferState.QUEUED)))
        assertTrue(state.showSendBar)
        assertTrue(state.awaitingReceipt)
        assertEquals(0, state.readyCount)
        assertTrue(state.canRetry(state.photos.single()))
        assertFalse(state.canRemove(state.photos.single()))
    }

    @Test fun busyBlocksEveryRetryAndRemoval() {
        val state = PhoneGalleryState(TransferState.entries.map(::photo), busy = true)
        assertTrue(state.showSendBar)
        state.photos.forEach {
            assertFalse(state.canRemove(it))
            assertFalse(state.canRetry(it))
        }
    }

    @Test fun retryAndRemovalFollowRepositoryRulesForEveryState() {
        val state = PhoneGalleryState()
        TransferState.entries.forEach { value ->
            assertEquals(value in listOf(TransferState.FAILED, TransferState.QUEUED), state.canRetry(photo(value)))
            assertEquals(value in listOf(TransferState.IDLE, TransferState.FAILED, TransferState.CONFIRMED), state.canRemove(photo(value)))
        }
    }

    @Test fun readyCountExcludesFailedQueuedAndConfirmedPhotos() {
        val state = PhoneGalleryState(listOf(photo(TransferState.IDLE), photo(TransferState.IDLE),
            photo(TransferState.FAILED), photo(TransferState.QUEUED), photo(TransferState.CONFIRMED)))
        assertEquals(2, state.readyCount)
    }
}
