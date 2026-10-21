package com.pixelwatchgallery.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.pixelwatchgallery.shared.PhotoProtocol
import com.pixelwatchgallery.shared.awaitReleasable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class PhotoReceiverService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        // The callback is already off main. Keep the service bound until the files and ACKs finish.
        runBlocking(Dispatchers.IO) {
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                val id = PhotoProtocol.idFromPath(item.uri.path.orEmpty()) ?: continue
                val sender = item.uri.host ?: continue
                var attempt = ""
                val failure = try {
                    val map = DataMapItem.fromDataItem(item).dataMap
                    val localNode = withTimeout(15_000) { Wearable.getNodeClient(this@PhotoReceiverService).localNode.await() }
                    if (map.getString("receiver") != localNode.id) continue
                    require(map.getString(PhotoProtocol.ID) == id) { "Transfer identifier mismatch." }
                    attempt = map.getString(PhotoProtocol.ATTEMPT).orEmpty()
                    val repository = PhotoRepository.get(applicationContext)
                    if (!repository.received(id)) {
                        val mime = map.getString(PhotoProtocol.MIME) ?: error("Missing image format.")
                        PhotoProtocol.extension(mime)
                        val asset = map.getAsset(PhotoProtocol.IMAGE) ?: error("Missing image data.")
                        var result: DataClient.GetFdForAssetResponse? = null
                        try {
                            // Assign inside the timeout so its exit cannot strand an acquired FD.
                            // If Play services completes later, awaitReleasable releases it instead.
                            withTimeout(60_000) {
                                result = Wearable.getDataClient(this@PhotoReceiverService).getFdForAsset(asset).awaitReleasable()
                            }
                            val input = result?.inputStream ?: error("Image asset is unavailable. Retry from the phone.")
                            input.use { repository.receive(id, mime, it) }
                        } finally { result?.release() }
                    } else { repository.refresh() }
                    null
                } catch (e: Exception) {
                    Log.w("GalleryReceiver", "Could not receive $id", e)
                    e.message ?: "Could not save image. Check free storage and retry."
                }
                // ACK transport failure says nothing about storage. Never send a negative
                // receipt merely because delivery of the successful receipt failed.
                runCatching { acknowledge(sender, id, attempt, failure == null, failure.orEmpty()) }
                    .onFailure { Log.w("GalleryReceiver", "Receipt delivery failed for $id; manual retry is safe", it) }
            }
        }
    }

    private suspend fun acknowledge(node: String, id: String, attempt: String, ok: Boolean, error: String = "") {
        val payload = JSONObject().apply {
            put(PhotoProtocol.ID, id); put(PhotoProtocol.ATTEMPT, attempt); put("ok", ok)
            if (!ok) put("error", error.take(512))
        }.toString().toByteArray()
        // An ACK can be lost. A manual retry changes the DataItem attempt, recognizes the receipt,
        // and resends this ACK without writing or resurrecting the photo.
        withTimeout(15_000) { Wearable.getMessageClient(this@PhotoReceiverService).sendMessage(node, PhotoProtocol.ACK, payload).await() }
    }
}
