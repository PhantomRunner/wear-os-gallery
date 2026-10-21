package com.pixelwatchgallery.wear

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

class GalleryApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes(8 * 1024 * 1024).build() }
        .diskCache(null) // Originals already live in private storage.
        .bitmapFactoryMaxParallelism(2)
        .build()
}
