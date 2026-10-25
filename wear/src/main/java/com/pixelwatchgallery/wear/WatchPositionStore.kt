package com.pixelwatchgallery.wear

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pixelwatchgallery.shared.PhotoProtocol
import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal enum class GalleryDestination { GALLERY, VIEWER }

internal data class WatchPosition(
    val destination: GalleryDestination = GalleryDestination.GALLERY,
    val photoId: String? = null,
    val gridAnchorId: String? = null,
    val gridOffset: Int = 0
) {
    fun resumePhoto(ids: List<String>): String? = photoId?.takeIf { destination == GalleryDestination.VIEWER && it in ids }
    fun gridIndex(ids: List<String>): Int = gridAnchorId?.let { id -> ids.indexOf(id).takeIf { it >= 0 }?.plus(1) } ?: 0
    fun gridOffset(ids: List<String>): Int = if (gridAnchorId == null || gridAnchorId in ids) gridOffset.coerceAtLeast(0) else 0
}

internal data class PositionState(val loaded: Boolean = false, val position: WatchPosition = WatchPosition())

/** One additive preference snapshot; never touches originals, receipts or the Data Layer. */
internal class WatchPositionStore(context: Context, name: String = "gallery_position") : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var gridSave: Job? = null
    private lateinit var preferences: SharedPreferences
    private val mutableState = MutableStateFlow(PositionState())
    val state = mutableState.asStateFlow()
    private val ready = scope.launch {
        val saved = runCatching {
            preferences = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            decode(preferences.getString("snapshot", null))
        }.getOrDefault(WatchPosition())
        mutableState.value = PositionState(true, saved)
    }

    init {
        scope.launch {
            ready.join()
            for (ignored in writes) writeLatest()
        }
    }

    @Synchronized private fun update(debounce: Boolean, transform: (WatchPosition) -> WatchPosition) {
        val current = state.value
        if (!current.loaded) return
        val next = transform(current.position)
        if (next == current.position) return
        mutableState.value = current.copy(position = next)
        gridSave?.cancel()
        if (debounce) gridSave = scope.launch { delay(150); writes.trySend(Unit) }
        else writes.trySend(Unit)
    }

    fun openPhoto(id: String) = update(false) { it.copy(destination = GalleryDestination.VIEWER, photoId = id) }
    fun showGallery() = update(false) { it.copy(destination = GalleryDestination.GALLERY) }
    fun saveGrid(anchorId: String?, offset: Int) = update(true) { it.copy(gridAnchorId = anchorId, gridOffset = offset.coerceAtLeast(0)) }

    @Synchronized fun flush() {
        gridSave?.cancel()
        if (state.value.loaded) writes.trySend(Unit)
    }

    /** Also permits disk round-trip verification without sleeping or resetting the singleton. */
    suspend fun awaitSaved() {
        ready.join()
        flush()
        writeLatest()
    }

    private suspend fun writeLatest() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            // Read inside the writer lock: a delayed request cannot overwrite a newer screen.
            val latest = state.value.position
            runCatching {
                check(::preferences.isInitialized)
                // Keep commit's result: a convenience edit block would discard write failure.
                val editor = preferences.edit()
                editor.putString("snapshot", encode(latest))
                check(editor.commit())
            }.onFailure { Log.w("WatchPosition", "Could not persist gallery position", it) }
        }
    }

    override fun close() { scope.cancel(); writes.close() }

    companion object {
        @Volatile private var instance: WatchPositionStore? = null
        fun get(context: Context): WatchPositionStore = instance ?: synchronized(this) {
            instance ?: WatchPositionStore(context).also { instance = it }
        }

        internal fun encode(position: WatchPosition): String = JSONObject().apply {
            put("screen", position.destination.name)
            put("photo", position.photoId ?: JSONObject.NULL)
            put("anchor", position.gridAnchorId ?: JSONObject.NULL)
            put("offset", position.gridOffset)
        }.toString()

        internal fun decode(value: String?): WatchPosition = runCatching {
            if (value == null) return WatchPosition()
            val json = JSONObject(value)
            fun id(key: String): String? = if (json.isNull(key)) null else json.getString(key).also {
                require(PhotoProtocol.validId(it))
            }
            WatchPosition(GalleryDestination.valueOf(json.getString("screen")), id("photo"), id("anchor"),
                json.getInt("offset").also { require(it >= 0) })
        }.getOrDefault(WatchPosition())
    }
}
