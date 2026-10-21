package com.pixelwatchgallery.wear

import android.content.Context
import com.pixelwatchgallery.shared.AtomicPhotoStore
import com.pixelwatchgallery.shared.PhotoValidation
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GalleryState(val photos: List<File> = emptyList(), val loaded: Boolean = false)

class PhotoRepository private constructor(context: Context) {
    // Lazily initialized only by IO operations, never during composition.
    private val store by lazy { AtomicPhotoStore(File(context.filesDir, "photos")) }
    private val mutableState = MutableStateFlow(GalleryState())
    val state = mutableState.asStateFlow()
    // Serialize each filesystem operation together with publication of its resulting snapshot.
    private val mutationLock = Mutex()
    private var indexed = false

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

    companion object {
        @Volatile private var instance: PhotoRepository? = null
        fun get(context: Context): PhotoRepository = instance ?: synchronized(this) {
            instance ?: PhotoRepository(context.applicationContext).also { instance = it }
        }
    }
}
