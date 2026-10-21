package com.pixelwatchgallery.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.net.toUri
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class TransferState { IDLE, PREPARING, WAITING_FOR_WATCH, SENDING, QUEUED, CONFIRMED, FAILED }
data class TransferPhoto(
    val id: String,
    val uri: String,
    val name: String,
    val state: TransferState = TransferState.IDLE,
    val detail: String = "Ready to send",
    val mime: String = "",
    val node: String = "",
    val dataUri: String = "",
    val attempt: String = "",
    val sizeBytes: Long? = null
) {
    fun withAcknowledgment(sourceNode: String, ackAttempt: String, ok: Boolean, error: String): TransferPhoto {
        if (node.isEmpty() || node != sourceNode) return this
        // A successful historical attempt still proves this UUID was saved. A failure only
        // describes its own attempt and cannot undo a confirmed receipt or a newer retry.
        return when {
            ok -> copy(state = TransferState.CONFIRMED, detail = "Confirmed · saved on watch")
            state != TransferState.CONFIRMED && attempt.isNotEmpty() && ackAttempt == attempt ->
                copy(state = TransferState.FAILED, detail = "Watch: $error")
            else -> this
        }
    }
}

/** Application-scoped: rotation or leaving the Activity does not cancel a manual send. */
class TransferRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val journal = AtomicFile(File(context.filesDir, "transfers.json"))
    private val outbox by lazy { File(context.filesDir, "outbox").apply { check(isDirectory || mkdirs()) { "Cannot create transfer storage." } } }
    private val mutablePhotos = MutableStateFlow<List<TransferPhoto>>(emptyList())
    val photos = mutablePhotos.asStateFlow()
    private val mutableBatch = MutableStateFlow<TransferBatch?>(null)
    val batch = mutableBatch.asStateFlow()
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
                    var size: Long? = null
                    val name = runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (!cursor.moveToFirst()) return@use null
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameColumn >= 0) cursor.getString(nameColumn) else null
                        }
                    }.getOrNull() ?: "Selected photo"
                    TransferPhoto(UUID.randomUUID().toString(), uri.toString(), name, sizeBytes = size)
                }
            edit { it + additions }
        } catch (e: Exception) { mutableNotice.value = "Could not save the selection: ${e.message}" }
    } }

    fun remove(id: String) { scope.launch {
        ready.join()
        val photo = photos.value.firstOrNull { it.id == id } ?: return@launch
        if (photo.state.isActive || photo.state == TransferState.QUEUED || busy.value) return@launch
        runCatching {
            edit { list -> list.filterNot { it.id == id } }
            File(outbox, id).delete()
        }.onFailure { mutableNotice.value = "Could not remove selection: ${it.message}" }
    } }

    fun sendPending() = send(null, newBatch = true)
    fun retry(id: String) = send(listOf(id), newBatch = false)
    fun dismissNotice() { mutableNotice.value = null }

    private fun send(requested: List<String>?, newBatch: Boolean) { scope.launch {
        ready.join()
        if (!sendMutex.tryLock()) return@launch
        mutableBusy.value = true
        try {
            val ids = (requested ?: photos.value.filter { it.state == TransferState.IDLE }.map { it.id })
                .distinct().filter { id -> photos.value.any { it.id == id && it.state != TransferState.CONFIRMED } }
            if (ids.isEmpty()) return@launch
            if (newBatch || mutableBatch.value == null) {
                val selected = photos.value.filter { it.id in ids }
                edit(TransferBatch(UUID.randomUUID().toString(), selected.map { BatchPhoto(it.id, it.sizeBytes, it.state) })) { it }
            }
            // Obtain exact staged sizes for the whole batch before any new DataItem is sent.
            // Unknown provider sizes never require reading an image into RAM.
            val prepared = ids.filter { prepareOne(it) }
            if (prepared.isEmpty()) return@launch
            prepared.forEach { id -> update(id) { if (it.state == TransferState.CONFIRMED) it else it.copy(
                state = TransferState.WAITING_FOR_WATCH, detail = "Checking watch connection…") } }
            check(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
                "Google Play services is unavailable. Install or update it, then retry."
            }
            val nodes = withTimeout(15_000) {
                Wearable.getCapabilityClient(context).getCapability(PhotoProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
            }
            check(nodes.isNotEmpty()) { "Watch app not reachable. Open Pixel Watch Gallery on your paired watch, connect the devices, then retry." }
            prepared.forEach { id -> update(id) { if (it.state == TransferState.CONFIRMED) it else it.copy(
                state = TransferState.SENDING, detail = "Ready · original file prepared") } }
            for (id in prepared) sendOne(id, nodes)
        } catch (e: Exception) {
            mutableNotice.value = e.message ?: "Could not start transfer. Check storage and the watch connection."
            val targets = requested ?: photos.value.filter { it.state.isActive }.map { it.id }
            targets.forEach { fail(it, e) }
        } finally { mutableBusy.value = false; sendMutex.unlock() }
    } }

    private fun prepareOne(id: String): Boolean {
        val original = photos.value.firstOrNull { it.id == id } ?: return false
        if (original.state == TransferState.CONFIRMED) return false
        return try {
            val attempt = UUID.randomUUID().toString()
            update(id) { if (it.state == TransferState.CONFIRMED) it else it.copy(state = TransferState.PREPARING,
                attempt = attempt, detail = "Reading original image…") }
            val staged = File(outbox, id)
            val mime: String
            if (staged.isFile && original.mime.isNotEmpty()) {
                mime = PhotoValidation.validate(staged, original.mime)
            } else {
                val temporary = File(outbox, "$id.part")
                try {
                    context.contentResolver.openInputStream(original.uri.toUri())?.use { input ->
                        FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
                    } ?: error("Cannot read this photo. Remove it and select it again.")
                    val detected = temporary.inputStream().use { stream ->
                        val header = ByteArray(12); stream.read(header); PhotoProtocol.detectMime(header)
                    } ?: error("Unsupported image format. Choose JPEG, PNG or WebP.")
                    val declared = context.contentResolver.getType(original.uri.toUri())
                    mime = PhotoValidation.validate(temporary, if (declared == null || declared == "application/octet-stream") detected else declared)
                    check(temporary.renameTo(staged)) { "Could not prepare image storage. Check free space and retry." }
                } finally { temporary.delete() }
            }
            update(id) { if (it.state == TransferState.CONFIRMED) it else it.copy(mime = mime, sizeBytes = staged.length(), detail = "Original prepared") }
            photos.value.firstOrNull { it.id == id }?.state != TransferState.CONFIRMED
        } catch (e: Exception) { fail(id, e); false }
    }

    private suspend fun sendOne(id: String, nodes: Set<com.google.android.gms.wearable.Node>) {
        val original = photos.value.firstOrNull { it.id == id } ?: return
        if (original.state == TransferState.CONFIRMED) return
        try {
            val node = if (original.node.isNotEmpty()) nodes.firstOrNull { it.id == original.node }
                else nodes.sortedWith(compareByDescending<com.google.android.gms.wearable.Node> { it.isNearby }.thenBy { it.id }).firstOrNull()
            checkNotNull(node) { "The original watch is not reachable. Connect it and retry." }
            val attempt = original.attempt
            val localNode = withTimeout(15_000) { Wearable.getNodeClient(context).localNode.await() }
            val dataUri = Uri.Builder().scheme("wear").authority(localNode.id).path(PhotoProtocol.PREFIX + id).build()
            update(id) { if (it.state == TransferState.CONFIRMED) it else it.copy(state = TransferState.SENDING,
                node = node.id, attempt = attempt, dataUri = dataUri.toString(), detail = "Submitting original image…") }
            if (photos.value.firstOrNull { it.id == id }?.state == TransferState.CONFIRMED) return
            ParcelFileDescriptor.open(File(outbox, id), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val request = PutDataMapRequest.create(PhotoProtocol.PREFIX + id).apply {
                    dataMap.putString(PhotoProtocol.ID, id)
                    dataMap.putString(PhotoProtocol.MIME, original.mime)
                    dataMap.putString(PhotoProtocol.ATTEMPT, attempt)
                    dataMap.putString("receiver", node.id)
                    dataMap.putAsset(PhotoProtocol.IMAGE, Asset.createFromFd(descriptor))
                }.asPutDataRequest().setUrgent()
                withTimeout(60_000) { Wearable.getDataClient(context).putDataItem(request).await() }
            }
            update(id) {
                if (it.state == TransferState.CONFIRMED || it.state == TransferState.FAILED) it
                else it.copy(state = TransferState.QUEUED, detail = "Waiting for watch receipt. Large originals may take several minutes.")
            }
            if (photos.value.firstOrNull { it.id == id }?.state == TransferState.CONFIRMED) {
                runCatching { withTimeout(15_000) { Wearable.getDataClient(context).deleteDataItems(dataUri).await() } }
            }
        } catch (e: Exception) { fail(id, e) }
    }

    private fun fail(id: String, error: Exception) {
        runCatching { update(id) {
            if (it.state == TransferState.CONFIRMED) it else it.copy(state = TransferState.FAILED,
                detail = if (error is kotlinx.coroutines.TimeoutCancellationException) "Connection timed out; delivery is unconfirmed. Connect the watch and retry."
                    else error.message ?: "Transfer failed. Check the connection and retry.")
        } }.onFailure { mutableNotice.value = "Could not save transfer status. Check phone storage and reopen the app." }
    }

    // Called on the WearableListenerService background thread, including when the UI is closed.
    suspend fun acknowledge(sourceNode: String, payload: ByteArray) {
        ready.join()
        if (payload.size > 8192) return
        val ack = JSONObject(payload.toString(Charsets.UTF_8))
        val id = ack.getString(PhotoProtocol.ID)
        if (!PhotoProtocol.validId(id)) return
        val ok = ack.optBoolean("ok")
        val photo = synchronized(this) {
            val current = photos.value.firstOrNull { it.id == id && it.node == sourceNode } ?: return
            val next = current.withAcknowledgment(sourceNode, ack.optString(PhotoProtocol.ATTEMPT), ok,
                ack.optString("error", "Could not save photo. Check free storage and retry."))
            // Check the current attempt and persist its state under the same lock as retry updates.
            if (next != current) update(id) { next }
            next.takeIf { ok && it.state == TransferState.CONFIRMED }
        } ?: return
        File(outbox, id).delete()
        if (photo.dataUri.isNotEmpty()) runCatching {
            withTimeout(15_000) { Wearable.getDataClient(context).deleteDataItems(photo.dataUri.toUri()).await() }
        }
    }

    @Synchronized private fun update(id: String, transform: (TransferPhoto) -> TransferPhoto) = edit { list -> list.map { if (it.id == id) transform(it) else it } }
    @Synchronized private fun edit(batch: TransferBatch? = mutableBatch.value, transform: (List<TransferPhoto>) -> List<TransferPhoto>) {
        val next = transform(mutablePhotos.value)
        val array = JSONArray()
        next.forEach { photo -> array.put(JSONObject().apply {
            put("id", photo.id); put("uri", photo.uri); put("name", photo.name); put("state", photo.state.name)
            put("detail", photo.detail); put("mime", photo.mime); put("node", photo.node); put("dataUri", photo.dataUri); put("attempt", photo.attempt)
            put("sizeBytes", photo.sizeBytes ?: JSONObject.NULL)
        }) }
        val nextBatch = batch?.reconcile(next)
        val root = JSONObject().put("photos", array).put("batch", nextBatch?.let { value ->
            JSONObject().put("id", value.id).put("photos", JSONArray().apply {
                value.photos.forEach { entry -> put(JSONObject().put("id", entry.id).put("sizeBytes", entry.sizeBytes ?: JSONObject.NULL).put("state", entry.state.name)) }
            })
        } ?: JSONObject.NULL)
        val output = journal.startWrite()
        try { output.write(root.toString().toByteArray()); journal.finishWrite(output) }
        catch (e: Exception) { journal.failWrite(output); throw e }
        mutablePhotos.value = next
        mutableBatch.value = nextBatch
    }
    @Synchronized private fun load() {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return
        // Read legacy v0.1 arrays as well as the additive v0.2 batch journal.
        val root = JSONTokener(journal.openRead().bufferedReader().use { it.readText() }).nextValue()
        val array = if (root is JSONArray) root else (root as JSONObject).getJSONArray("photos")
        val savedBatch = (root as? JSONObject)?.optJSONObject("batch")?.let { batch ->
            val entries = batch.getJSONArray("photos")
            TransferBatch(batch.getString("id"), (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val state = TransferState.valueOf(entry.getString("state"))
                BatchPhoto(entry.getString("id"), entry.sizeOrNull(), if (state.isActive) TransferState.FAILED else state)
            })
        }
        val loaded = (0 until array.length()).map { index ->
            val p = array.getJSONObject(index)
            val old = TransferState.valueOf(p.getString("state"))
            TransferPhoto(p.getString("id"), p.getString("uri"), p.getString("name"),
                if (old.isActive) TransferState.FAILED else old,
                if (old.isActive) "Preparation interrupted. Tap Retry." else p.getString("detail"),
                p.optString("mime"), p.optString("node"), p.optString("dataUri"), p.optString("attempt"), p.sizeOrNull())
        }
        edit(savedBatch) { loaded }
        outbox.listFiles()?.filter { it.name.endsWith(".part") || loaded.none { p -> p.id == it.name && p.state != TransferState.CONFIRMED } }?.forEach { it.delete() }
    }

    private fun JSONObject.sizeOrNull(): Long? =
        if (has("sizeBytes") && !isNull("sizeBytes")) getLong("sizeBytes").takeIf { it >= 0 } else null

    companion object {
        // The singleton retains only applicationContext, never an Activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: TransferRepository? = null
        fun get(context: Context): TransferRepository = instance ?: synchronized(this) {
            instance ?: TransferRepository(context.applicationContext).also { instance = it }
        }
    }
}
