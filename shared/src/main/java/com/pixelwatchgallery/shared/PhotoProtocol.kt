package com.pixelwatchgallery.shared

import java.util.UUID

object PhotoProtocol {
    const val PREFIX = "/transfer/photo/"
    const val ACK = "/transfer/ack"
    const val CAPABILITY = "pixel_watch_gallery_receiver_v1"
    const val ID = "uuid"
    const val MIME = "mime"
    const val IMAGE = "image"
    const val ATTEMPT = "attempt"
    val extensions = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp")

    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    fun idFromPath(path: String): String? = path.takeIf { it.startsWith(PREFIX) }
        ?.removePrefix(PREFIX)?.takeIf(::validId)
    fun extension(mime: String): String = extensions[mime]
        ?: throw IllegalArgumentException("Unsupported image format. Choose JPEG, PNG or WebP.")
    fun detectMime(header: ByteArray): String? = when {
        header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "image/jpeg"
        header.size >= 8 && header.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
        header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}
