package com.pixelwatchgallery.mobile

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.pixelwatchgallery.shared.PhotoProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class AcknowledgmentService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PhotoProtocol.ACK) return
        // WearableListenerService callbacks run on a worker thread. Complete work before returning.
        runBlocking(Dispatchers.IO) {
            runCatching { TransferRepository.get(applicationContext).acknowledge(event.sourceNodeId, event.data) }
                .onFailure { Log.w("GalleryAck", "Could not handle receipt; manual retry is safe", it) }
        }
    }
}
