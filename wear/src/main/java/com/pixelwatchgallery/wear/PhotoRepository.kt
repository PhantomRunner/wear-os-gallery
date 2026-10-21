package com.pixelwatchgallery.wear

import android.content.Context
import com.pixelwatchgallery.shared.AtomicPhotoStore
import com.pixelwatchgallery.shared.PhotoValidation
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class PhotoRepository private constructor(context: Context) {
    // Lazily initialized only by IO operations, never during composition.
    private val store by lazy { AtomicPhotoStore(File(context.filesDir, "photos")) }
    private val mutablePhotos = MutableStateFlow<List<File>>(emptyList())
    val photos = mutablePhotos.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) { mutablePhotos.value = store.list() }
    suspend fun received(id: String) = withContext(Dispatchers.IO) { store.received(id) }
    suspend fun receive(id: String, mime: String, input: InputStream) = withContext(Dispatchers.IO) {
        store.save(id, mime, input) { file, type -> PhotoValidation.validate(file, type); Unit }
        refresh()
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { store.delete(id); refresh() }

    companion object {
        @Volatile private var instance: PhotoRepository? = null
        fun get(context: Context): PhotoRepository = instance ?: synchronized(this) {
            instance ?: PhotoRepository(context.applicationContext).also { instance = it }
        }
    }
}
