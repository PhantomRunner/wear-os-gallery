package com.pixelwatchgallery.mobile

/** Presentation only. Receipt and delivery rules remain owned by TransferRepository. */
internal data class PhoneGalleryState(
    val photos: List<TransferPhoto> = emptyList(),
    val progress: TransferProgress? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val transferId: String? = null
) {
    val selected get() = photos.filter { it.state != TransferState.CONFIRMED }
    val sent get() = photos.filter { it.state == TransferState.CONFIRMED }
    val readyCount get() = photos.count { it.state == TransferState.IDLE }
    val awaitingReceipt get() = selected.any { it.state == TransferState.QUEUED }
    val showSendBar get() = readyCount > 0 || busy || awaitingReceipt
    fun canRetry(photo: TransferPhoto) = !busy && photo.state in setOf(TransferState.FAILED, TransferState.QUEUED)
    fun canRemove(photo: TransferPhoto) = !busy && !photo.state.isActive && photo.state != TransferState.QUEUED
}
