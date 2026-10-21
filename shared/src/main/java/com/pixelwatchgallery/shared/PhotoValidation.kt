package com.pixelwatchgallery.shared

import android.graphics.BitmapFactory
import java.io.File

object PhotoValidation {
    // Only sampled decoding for validation; the original file is never rewritten.
    fun validate(file: File, declaredMime: String): String {
        PhotoProtocol.extension(declaredMime)
        val header = file.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
        val actual = PhotoProtocol.detectMime(header)
        require(actual == declaredMime) { "The image bytes do not match their MIME type ($declaredMime)." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image is damaged or unreadable." }
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("This image could not be decoded.")
        bitmap.recycle()
        return actual
    }
}
