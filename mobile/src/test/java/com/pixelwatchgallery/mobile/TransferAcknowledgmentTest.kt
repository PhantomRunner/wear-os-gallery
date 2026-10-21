package com.pixelwatchgallery.mobile

import org.junit.Assert.*
import org.junit.Test

class TransferAcknowledgmentTest {
    private val queued = TransferPhoto("id", "content://photo", "photo.jpg",
        state = TransferState.QUEUED, node = "watch", attempt = "new-attempt")

    @Test fun oldFailureCannotRegressNewRetry() {
        assertEquals(queued, queued.withAcknowledgment("watch", "old-attempt", false, "Storage full"))
    }

    @Test fun currentFailureIsRetryable() {
        val failed = queued.withAcknowledgment("watch", "new-attempt", false, "Storage full")
        assertEquals(TransferState.FAILED, failed.state)
        assertEquals("Watch: Storage full", failed.detail)
    }

    @Test fun historicalSuccessConfirmsSameUuidDuringRetry() {
        val retrying = queued.copy(state = TransferState.PREPARING)
        assertEquals(TransferState.CONFIRMED,
            retrying.withAcknowledgment("watch", "old-attempt", true, "").state)
    }

    @Test fun failureCannotUndoConfirmation() {
        val confirmed = queued.withAcknowledgment("watch", "new-attempt", true, "")
        assertEquals(confirmed, confirmed.withAcknowledgment("watch", "new-attempt", false, "Disconnected"))
    }

    @Test fun otherNodeCannotConfirmOrFailTransfer() {
        assertEquals(queued, queued.withAcknowledgment("other-watch", "new-attempt", true, ""))
        assertEquals(queued, queued.withAcknowledgment("other-watch", "new-attempt", false, "Storage full"))
    }
}
