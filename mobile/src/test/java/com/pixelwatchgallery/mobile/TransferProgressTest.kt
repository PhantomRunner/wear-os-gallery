package com.pixelwatchgallery.mobile

import org.junit.Assert.*
import org.junit.Test

class TransferProgressTest {
    private fun photo(id: String, bytes: Long?, state: TransferState = TransferState.QUEUED) =
        TransferPhoto(id, "content://$id", "$id.jpg", state = state, node = "watch", attempt = "attempt", sizeBytes = bytes)
    private fun batch(vararg photos: TransferPhoto) = TransferBatch("batch", photos.map { BatchPhoto(it.id, it.sizeBytes, it.state) })

    @Test fun submittedDataItemsDoNotCountAsReceived() {
        val progress = batch(photo("a", 1_000_000), photo("b", 5_000_000)).progress
        assertEquals(0, progress.received)
        assertEquals(0L, progress.confirmedBytes)
        assertEquals(0, progress.percent)
        assertEquals(BatchPhase.WAITING_FOR_CONFIRMATION, progress.phase)
    }

    @Test fun progressIsWeightedByOriginalBytes() {
        val progress = batch(photo("a", 200_000, TransferState.CONFIRMED), photo("b", 800_000)).progress
        assertEquals(1, progress.received)
        assertEquals(200_000L, progress.confirmedBytes)
        assertEquals(1_000_000L, progress.totalBytes)
        assertEquals(20, progress.percent)
    }

    @Test fun duplicateAcknowledgmentsDoNotInflateProgress() {
        val a = photo("a", 1_000_000)
        val b = photo("b", 4_000_000)
        val confirmed = a.withAcknowledgment("watch", "attempt", true, "")
        val first = batch(a, b).reconcile(listOf(confirmed, b))
        val duplicate = first.reconcile(listOf(confirmed.withAcknowledgment("watch", "attempt", true, ""), b))
        assertEquals(first.progress, duplicate.progress)
        assertEquals(20, duplicate.progress.percent)
        assertEquals(1, duplicate.progress.received)
    }

    @Test fun previousBatchAcknowledgmentCannotAffectCurrentSelection() {
        val current = photo("current", 2_000_000)
        val old = photo("previous", 8_000_000, TransferState.CONFIRMED)
        val progress = batch(current).reconcile(listOf(current, old)).progress
        assertEquals(0, progress.received)
        assertEquals(2_000_000L, progress.totalBytes)
        assertEquals(0, progress.percent)
    }

    @Test fun failureNeverCountsAsCompleted() {
        val progress = batch(photo("a", 1_000_000, TransferState.CONFIRMED), photo("b", 1_000_000, TransferState.FAILED)).progress
        assertEquals(50, progress.percent)
        assertEquals(1, progress.received)
        assertEquals(BatchPhase.FAILED, progress.phase)
    }

    @Test fun allAcknowledgmentsAreRequiredEvenWhenLastPhotoIsTiny() {
        val progress = batch(photo("a", 5_000_000_000, TransferState.CONFIRMED), photo("b", 1)).progress
        assertEquals(99, progress.percent)
        assertNotEquals(BatchPhase.COMPLETED, progress.phase)
    }

    @Test fun completionRequiresEveryAcknowledgment() {
        val progress = batch(photo("a", 200_000, TransferState.CONFIRMED), photo("b", 6_000_000, TransferState.CONFIRMED)).progress
        assertEquals(100, progress.percent)
        assertEquals(2, progress.received)
        assertEquals(progress.totalBytes, progress.confirmedBytes)
        assertEquals(BatchPhase.COMPLETED, progress.phase)
    }

    @Test fun unknownProviderSizeDoesNotInventPercentage() {
        val initial = batch(photo("a", null))
        assertNull(initial.progress.totalBytes)
        assertNull(initial.progress.percent)
        val staged = initial.reconcile(listOf(photo("a", 2_000_000)))
        assertEquals(2_000_000L, staged.progress.totalBytes)
        assertEquals(0, staged.progress.percent)
    }

    @Test fun actualStagedSizeReplacesProviderMetadata() {
        val updated = batch(photo("a", 1)).reconcile(listOf(photo("a", 7_000_000)))
        assertEquals(7_000_000L, updated.progress.totalBytes)
    }

    @Test fun removingHistoryOrAddingSelectionDoesNotChangeBatch() {
        val a = photo("a", 1_000_000, TransferState.CONFIRMED)
        val b = photo("b", 4_000_000)
        val original = batch(a, b)
        val updated = original.reconcile(listOf(b, photo("new", 20_000_000)))
        assertEquals(original.progress, updated.progress)
    }

    @Test fun zeroBytesCannotCompleteWithoutAcknowledgment() {
        val progress = batch(photo("a", 0)).progress
        assertEquals(0, progress.percent)
        assertNotEquals(BatchPhase.COMPLETED, progress.phase)
        assertEquals(100, batch(photo("a", 0, TransferState.CONFIRMED)).progress.percent)
    }

    @Test fun metadataOverflowCannotProduceInvalidPercentage() {
        val progress = batch(photo("a", Long.MAX_VALUE), photo("b", Long.MAX_VALUE)).progress
        assertNull(progress.totalBytes)
        assertNull(progress.percent)
    }

    @Test fun duplicatePhotoIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { batch(photo("a", 1), photo("a", 1)) }
    }

    @Test fun allSupportedSizeRangesRemainEligible() {
        for (size in listOf(200_000L, 1_000_000L, 5_000_000L, 6_000_000L, 100_000_000L)) {
            val progress = batch(photo("a", size)).progress
            assertEquals(size, progress.totalBytes)
            assertEquals(BatchPhase.WAITING_FOR_CONFIRMATION, progress.phase)
        }
    }
}
