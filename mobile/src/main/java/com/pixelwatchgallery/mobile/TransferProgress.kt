package com.pixelwatchgallery.mobile

const val LARGE_PHOTO_BYTES = 5_000_000L

enum class BatchPhase(val label: String) {
    IDLE("Idle"), PREPARING("Preparing"), WAITING_FOR_WATCH("Waiting for watch"),
    SENDING("Sending"), WAITING_FOR_CONFIRMATION("Waiting for confirmation"),
    COMPLETED("Completed"), FAILED("Failed")
}

val TransferState.isActive: Boolean
    get() = this == TransferState.PREPARING || this == TransferState.WAITING_FOR_WATCH || this == TransferState.SENDING

val TransferState.label: String
    get() = when (this) {
        TransferState.WAITING_FOR_WATCH -> "Waiting for watch"
        TransferState.SENDING -> "Sending"
        TransferState.QUEUED -> "Waiting for confirmation"
        else -> name.lowercase().replaceFirstChar { it.uppercase() }
    }

data class BatchPhoto(val id: String, val sizeBytes: Long?, val state: TransferState)

data class TransferProgress(
    val phase: BatchPhase,
    val received: Int,
    val count: Int,
    val confirmedBytes: Long,
    val totalBytes: Long?,
    val percent: Int?,
    val failed: Int
)

/** A fixed selection snapshot: later selections/removals and unrelated ACKs cannot change it. */
data class TransferBatch(val id: String, val photos: List<BatchPhoto>) {
    init {
        require(photos.map { it.id }.distinct().size == photos.size)
        require(photos.all { it.sizeBytes == null || it.sizeBytes >= 0 })
    }

    fun reconcile(current: List<TransferPhoto>): TransferBatch {
        val byId = current.associateBy { it.id }
        return copy(photos = photos.map { entry ->
            byId[entry.id]?.let { BatchPhoto(it.id, it.sizeBytes, it.state) } ?: entry
        })
    }

    val progress: TransferProgress get() {
        val received = photos.count { it.state == TransferState.CONFIRMED }
        val complete = photos.isNotEmpty() && received == photos.size
        val total = sumBytes(photos)
        val confirmed = sumBytes(photos.filter { it.state == TransferState.CONFIRMED }) ?: 0L
        val phase = when {
            photos.isEmpty() -> BatchPhase.IDLE
            complete -> BatchPhase.COMPLETED
            photos.any { it.state == TransferState.PREPARING } -> BatchPhase.PREPARING
            photos.any { it.state == TransferState.SENDING } -> BatchPhase.SENDING
            photos.any { it.state == TransferState.WAITING_FOR_WATCH } -> BatchPhase.WAITING_FOR_WATCH
            photos.any { it.state == TransferState.FAILED } -> BatchPhase.FAILED
            photos.any { it.state == TransferState.QUEUED } -> BatchPhase.WAITING_FOR_CONFIRMATION
            else -> BatchPhase.PREPARING
        }
        val percent = total?.let {
            when {
                complete -> 100
                it == 0L -> 0
                else -> (confirmed.toDouble() / it * 100).toInt().coerceIn(0, 99)
            }
        }
        return TransferProgress(phase, received, photos.size, confirmed, total, percent,
            photos.count { it.state == TransferState.FAILED })
    }

    private fun sumBytes(entries: List<BatchPhoto>): Long? {
        var sum = 0L
        for (photo in entries) {
            val size = photo.sizeBytes ?: return null
            if (Long.MAX_VALUE - sum < size) return null
            sum += size
        }
        return sum
    }
}
