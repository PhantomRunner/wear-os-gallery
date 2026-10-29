package com.pixelwatchgallery.wear

import android.content.Context
import com.pixelwatchgallery.shared.AtomicPhotoStore
import com.pixelwatchgallery.shared.PhotoValidation
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GalleryState(val photos: List<File> = emptyList(), val loaded: Boolean = false)

class PhotoRepository internal constructor(context: Context) {
    // Lazily initialized only by IO operations, never during composition.
    private val store by lazy { AtomicPhotoStore(File(context.filesDir, "photos")) }
    private val mutableState = MutableStateFlow(GalleryState())
    val state = mutableState.asStateFlow()
    // Serialize each filesystem operation together with publication of its resulting snapshot.
    private val mutationLock = Mutex()
    private var indexed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableDeletion = MutableStateFlow(BatchDeletion())
    internal val deletion = mutableDeletion.asStateFlow()

    /** Lives beyond Activity recreation. Only one confirmed batch can run at a time. */
    @Synchronized internal fun startDeletion(ids: Set<String>) {
        if (ids.isEmpty() || deletion.value.running) return
        val snapshot = ids.toSet()
        mutableDeletion.value = BatchDeletion(snapshot, running = true)
        scope.launch {
            val result = try {
                deleteMany(snapshot) { count ->
                    mutableDeletion.value = BatchDeletion(snapshot, completed = count, running = true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                BatchDeletionResult(emptySet(), snapshot.associateWith { e.message.orEmpty() })
            }
            mutableDeletion.value = BatchDeletion(snapshot, snapshot.size, result = result)
        }
    }

    @Synchronized internal fun dismissDeletionResult() {
        if (!deletion.value.running) mutableDeletion.value = BatchDeletion()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            if (indexed) return@withLock
            try { mutableState.value = GalleryState(store.list(), loaded = true); indexed = true }
            catch (e: Exception) {
                mutableState.value = mutableState.value.copy(loaded = true)
                throw e
            }
        }
    }
    suspend fun received(id: String) = withContext(Dispatchers.IO) { store.received(id) }
    suspend fun receive(id: String, mime: String, input: InputStream) = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            val saved = store.save(id, mime, input) { file, type -> PhotoValidation.validate(file, type); Unit }
            if (saved || !indexed) {
                mutableState.value = GalleryState(store.list(), loaded = true)
                indexed = true
            }
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            store.delete(id)
            mutableState.value = GalleryState(store.list(), loaded = true)
            indexed = true
        }
    }

    internal suspend fun deleteMany(ids: Set<String>, progress: (Int) -> Unit = {}): BatchDeletionResult = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            val deleted = mutableSetOf<String>()
            val failures = mutableMapOf<String, String>()
            try {
                ids.forEachIndexed { index, id ->
                    try { store.delete(id); deleted += id }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failures[id] = e.message.orEmpty()
                    }
                    progress(index + 1)
                }
            } finally {
                mutableState.value = GalleryState(store.list(), loaded = true)
                indexed = true
            }
            BatchDeletionResult(deleted, failures)
        }
    }

    companion object {
        @Volatile private var instance: PhotoRepository? = null
        fun get(context: Context): PhotoRepository = instance ?: synchronized(this) {
            instance ?: PhotoRepository(context.applicationContext).also { instance = it }
        }
    }
}
