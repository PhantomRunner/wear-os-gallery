package com.pixelwatchgallery.shared

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Call from an IO thread. The same instance serializes reception and deletion. */
class AtomicPhotoStore(private val directory: File) {
    private val receipts = File(directory, ".receipts")

    init {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create photo storage." }
        check(receipts.isDirectory || receipts.mkdirs()) { "Cannot create receipt storage." }
        directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    @Synchronized fun list(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension in PhotoProtocol.extensions.values && PhotoProtocol.validId(it.nameWithoutExtension) }
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })

    @Synchronized fun received(id: String): Boolean {
        require(PhotoProtocol.validId(id))
        return File(receipts, id).isFile || PhotoProtocol.extensions.values.any { File(directory, "$id.$it").isFile }
    }

    @Synchronized fun save(id: String, mime: String, input: InputStream, validate: (File, String) -> Unit): Boolean {
        require(PhotoProtocol.validId(id)) { "Invalid transfer identifier." }
        val extension = PhotoProtocol.extension(mime)
        if (received(id)) return false
        val temporary = File(directory, "$id.part")
        try {
            FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
            validate(temporary, mime)
            Files.move(temporary.toPath(), File(directory, "$id.$extension").toPath(), StandardCopyOption.ATOMIC_MOVE)
            recordReceipt(id)
            return true
        } finally { temporary.delete() }
    }

    @Synchronized fun delete(id: String) {
        require(PhotoProtocol.validId(id))
        // Retain a tiny tombstone: retrying a lost ACK must not resurrect a deleted photo.
        recordReceipt(id)
        PhotoProtocol.extensions.values.forEach { extension ->
            val file = File(directory, "$id.$extension")
            check(!file.exists() || file.delete()) { "Could not delete the photo. Please try again." }
        }
    }

    private fun recordReceipt(id: String) {
        FileOutputStream(File(receipts, id)).use { it.fd.sync() }
    }
}
