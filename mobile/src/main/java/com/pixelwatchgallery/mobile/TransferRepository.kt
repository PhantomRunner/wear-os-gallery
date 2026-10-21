package com.pixelwatchgallery.mobile

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.pixelwatchgallery.shared.PhotoProtocol
import com.pixelwatchgallery.shared.PhotoValidation
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

enum class TransferState { IDLE, PREPARING, QUEUED, CONFIRMED, FAILED }
data class TransferPhoto(
    val id: String,
    val uri: String,
    val name: String,
    val state: TransferState = TransferState.IDLE,
    val detail: String = "Ready to send",
    val mime: String = "",
    val node: String = "",
    val dataUri: String = "",
    val attempt: String = ""
)

/** Application-scoped: rotation or leaving the Activity does not cancel a manual send. */
class TransferRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val journal = AtomicFile(File(context.filesDir, "transfers.json"))
    private val outbox = File(context.filesDir, "outbox").apply { mkdirs() }
    private val mutablePhotos = MutableStateFlow<List<TransferPhoto>>(emptyList())
    val photos = mutablePhotos.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val ready = scope.launch {
        runCatching { load() }.onFailure { mutableNotice.value = "Could not read saved selections. Select your photos again." }
    }

    fun select(uris: List<Uri>) { scope.launch {
        ready.join()
        try {
            val additions = uris.distinct().filter { uri -> photos.value.none { it.uri == uri.toString() && it.state != TransferState.CONFIRMED } }
                .map { uri ->
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val name = runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    }.getOrNull() ?: "Selected photo"
                    TransferPhoto(UUID.randomUUID().toString(), uri.toString(), name)
                }
            edit { it + additions }
        } catch (e: Exception) { mutableNotice.value = "Could not save the selection: ${e.message}" }
    } }

    fun remove(id: String) { scope.launch {
        ready.join()
        val photo = photos.value.firstOrNull { it.id == id } ?: return@launch
        if (photo.state == TransferState.PREPARING || photo.state == TransferState.QUEUED) return@launch
        runCatching {
            edit { list -> list.filterNot { it.id == id } }
            File(outbox, id).delete()
        }.onFailure { mutableNotice.value = "Could not remove selection: ${it.message}" }
    } }

    fun sendPending() = send(photos.value.filter { it.state == TransferState.IDLE }.map { it.id })
    fun retry(id: String) = send(listOf(id))
    fun dismissNotice() { mutableNotice.value = null }

    private fun send(ids: List<String>) { scope.launch {
        ready.join()
        if (!sendMutex.tryLock()) return@launch
        mutableBusy.value = true
        try { ids.forEach { sendOne(it) } }
        finally { mutableBusy.value = false; sendMutex.unlock() }
    } }

    private suspend fun sendOne(id: String) {
        val original = photos.value.firstOrNull { it.id == id } ?: return
        if (original.state == TransferState.CONFIRMED) return
        try {
            update(id) { it.copy(state = TransferState.PREPARING, detail = "Checking watch connection…") }
            check(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
                "Google Play services is unavailable. Install or update it, then retry."
            }
            val nodes = withTimeout(15_000) {
                Wearable.getCapabilityClient(context).getCapability(PhotoProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
            }
            val node = if (original.node.isNotEmpty()) nodes.firstOrNull { it.id == original.node }
                else nodes.sortedWith(compareByDescending<com.google.android.gms.wearable.Node> { it.isNearby }.thenBy { it.id }).firstOrNull()
            checkNotNull(node) { "Watch app not reachable. Install and open Pixel Watch Gallery on your paired watch, connect the devices, then retry." }
            val attempt = UUID.randomUUID().toString()
            update(id) { it.copy(node = node.id, attempt = attempt, detail = "Reading original image…") }
            val staged = File(outbox, id)
            val mime: String
            if (staged.isFile && original.mime.isNotEmpty()) {
                mime = PhotoValidation.validate(staged, original.mime)
            } else {
                val temporary = File(outbox, "$id.part")
                try {
                    context.contentResolver.openInputStream(Uri.parse(original.uri))?.use { input ->
                        FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
                    } ?: error("Cannot read this photo. Remove it and select it again.")
                    val detected = temporary.inputStream().use { stream ->
                        val header = ByteArray(12); stream.read(header); PhotoProtocol.detectMime(header)
                    } ?: error("Unsupported image format. Choose JPEG, PNG or WebP.")
                    val declared = context.contentResolver.getType(Uri.parse(original.uri))
                    mime = PhotoValidation.validate(temporary, if (declared == null || declared == "application/octet-stream") detected else declared)
                    check(temporary.renameTo(staged)) { "Could not prepare image storage. Check free space and retry." }
                } finally { temporary.delete() }
            }
            val localNode = withTimeout(15_000) { Wearable.getNodeClient(context).localNode.await() }
            val dataUri = Uri.Builder().scheme("wear").authority(localNode.id).path(PhotoProtocol.PREFIX + id).build()
            update(id) { it.copy(state = TransferState.QUEUED, mime = mime, dataUri = dataUri.toString(), detail = "Submitting original image…") }
            ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val request = PutDataMapRequest.create(PhotoProtocol.PREFIX + id).apply {
                    dataMap.putString(PhotoProtocol.ID, id)
                    dataMap.putString(PhotoProtocol.MIME, mime)
                    dataMap.putString(PhotoProtocol.ATTEMPT, attempt)
                    dataMap.putString("receiver", node.id)
                    dataMap.putAsset(PhotoProtocol.IMAGE, Asset.createFromFd(descriptor))
                }.asPutDataRequest().setUrgent()
                withTimeout(60_000) { Wearable.getDataClient(context).putDataItem(request).await() }
            }
            update(id) {
                if (it.state == TransferState.CONFIRMED || it.state == TransferState.FAILED) it
                else it.copy(state = TransferState.QUEUED, detail = "Queued · waiting for watch receipt. Large originals may take several minutes.")
            }
        } catch (e: Exception) {
            runCatching { update(id) {
                if (it.state == TransferState.CONFIRMED) it else it.copy(state = TransferState.FAILED,
                    detail = if (e is kotlinx.coroutines.TimeoutCancellationException) "Connection timed out; delivery is unconfirmed. Connect the watch and retry." else e.message ?: "Transfer failed. Check the connection and retry.")
            } }.onFailure { mutableNotice.value = "Could not save transfer status. Check phone storage and reopen the app." }
        }
    }

    // Called on the WearableListenerService background thread, including when the UI is closed.
    suspend fun acknowledge(sourceNode: String, payload: ByteArray) {
        ready.join()
        if (payload.size > 8192) return
        val ack = JSONObject(payload.toString(Charsets.UTF_8))
        val id = ack.getString(PhotoProtocol.ID)
        if (!PhotoProtocol.validId(id)) return
        val photo = photos.value.firstOrNull { it.id == id && it.node == sourceNode } ?: return
        if (ack.optBoolean("ok")) {
            update(id) { it.copy(state = TransferState.CONFIRMED, detail = "Confirmed · saved on watch") }
            File(outbox, id).delete()
            if (photo.dataUri.isNotEmpty()) runCatching {
                withTimeout(15_000) { Wearable.getDataClient(context).deleteDataItems(Uri.parse(photo.dataUri)).await() }
            }
        } else if (ack.optString(PhotoProtocol.ATTEMPT) == photo.attempt) {
            update(id) { if (it.state == TransferState.CONFIRMED) it else it.copy(state = TransferState.FAILED,
                detail = "Watch: ${ack.optString("error", "Could not save photo. Check free storage and retry.")}") }
        }
    }

    @Synchronized private fun update(id: String, transform: (TransferPhoto) -> TransferPhoto) = edit { list -> list.map { if (it.id == id) transform(it) else it } }
    @Synchronized private fun edit(transform: (List<TransferPhoto>) -> List<TransferPhoto>) {
        val next = transform(mutablePhotos.value)
        val array = JSONArray()
        next.forEach { photo -> array.put(JSONObject().apply {
            put("id", photo.id); put("uri", photo.uri); put("name", photo.name); put("state", photo.state.name)
            put("detail", photo.detail); put("mime", photo.mime); put("node", photo.node); put("dataUri", photo.dataUri); put("attempt", photo.attempt)
        }) }
        val output = journal.startWrite()
        try { output.write(array.toString().toByteArray()); journal.finishWrite(output) }
        catch (e: Exception) { journal.failWrite(output); throw e }
        mutablePhotos.value = next
    }
    @Synchronized private fun load() {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return
        val array = JSONArray(journal.openRead().bufferedReader().use { it.readText() })
        val loaded = (0 until array.length()).map { index ->
            val p = array.getJSONObject(index)
            val old = TransferState.valueOf(p.getString("state"))
            TransferPhoto(p.getString("id"), p.getString("uri"), p.getString("name"),
                if (old == TransferState.PREPARING) TransferState.FAILED else old,
                if (old == TransferState.PREPARING) "Preparation interrupted. Tap Retry." else p.getString("detail"),
                p.optString("mime"), p.optString("node"), p.optString("dataUri"), p.optString("attempt"))
        }
        edit { loaded }
        outbox.listFiles()?.filter { it.name.endsWith(".part") || loaded.none { p -> p.id == it.name && p.state != TransferState.CONFIRMED } }?.forEach { it.delete() }
    }

    companion object {
        @Volatile private var instance: TransferRepository? = null
        fun get(context: Context): TransferRepository = instance ?: synchronized(this) {
            instance ?: TransferRepository(context.applicationContext).also { instance = it }
        }
    }
}
