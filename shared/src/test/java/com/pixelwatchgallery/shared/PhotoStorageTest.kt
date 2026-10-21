package com.pixelwatchgallery.shared

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PhotoStorageTest {
    private fun withStore(block: (AtomicPhotoStore, File) -> Unit) {
        val directory = Files.createTempDirectory("gallery-test").toFile()
        try { block(AtomicPhotoStore(directory), directory) } finally { directory.deleteRecursively() }
    }

    @Test fun originalBytesPersistAcrossRestart() = withStore { store, dir ->
        val id = UUID.randomUUID().toString()
        val bytes = ByteArray(131072) { (it % 251).toByte() }
        assertTrue(store.save(id, "image/jpeg", ByteArrayInputStream(bytes)) { _, _ -> })
        val restored = AtomicPhotoStore(dir)
        assertArrayEquals(bytes, restored.list().single().readBytes())
        assertTrue(restored.received(id))
    }

    @Test fun repeatedTransferDoesNotOverwriteExistingPhoto() = withStore { store, _ ->
        val id = UUID.randomUUID().toString()
        store.save(id, "image/png", ByteArrayInputStream(byteArrayOf(1, 2))) { _, _ -> }
        assertFalse(store.save(id, "image/png", ByteArrayInputStream(byteArrayOf(9))) { _, _ -> fail("Duplicates must not be decoded again") })
        assertArrayEquals(byteArrayOf(1, 2), store.list().single().readBytes())
    }

    @Test fun failedValidationLeavesNoPartialPhotoOrReceipt() = withStore { store, dir ->
        val id = UUID.randomUUID().toString()
        assertThrows(IllegalArgumentException::class.java) {
            store.save(id, "image/webp", ByteArrayInputStream(byteArrayOf(1))) { _, _ -> throw IllegalArgumentException("corrupt") }
        }
        assertTrue(store.list().isEmpty())
        assertFalse(store.received(id))
        assertFalse(File(dir, "$id.part").exists())
    }

    @Test fun deletionSurvivesRestartAndLostAcknowledgmentRetry() = withStore { store, dir ->
        val id = UUID.randomUUID().toString()
        store.save(id, "image/jpeg", ByteArrayInputStream(byteArrayOf(1))) { _, _ -> }
        store.delete(id)
        val restored = AtomicPhotoStore(dir)
        assertTrue(restored.list().isEmpty())
        assertTrue(restored.received(id))
        assertFalse(restored.save(id, "image/jpeg", ByteArrayInputStream(byteArrayOf(1))) { _, _ -> })
        assertTrue(restored.list().isEmpty())
    }

    @Test fun restartRemovesInterruptedTemporaryFiles() = withStore { _, dir ->
        val partial = File(dir, "${UUID.randomUUID()}.part").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(AtomicPhotoStore(dir).list().isEmpty())
        assertFalse(partial.exists())
    }

    @Test fun interruptedInputLeavesNoPhotoAndCanBeRetried() = withStore { store, dir ->
        val id = UUID.randomUUID().toString()
        val interrupted = object : InputStream() {
            private var reads = 0
            override fun read(): Int = if (reads++ < 16) 1 else throw IOException("Asset disconnected")
        }
        assertThrows(IOException::class.java) {
            store.save(id, "image/jpeg", interrupted) { _, _ -> fail("Incomplete files must not be validated") }
        }
        assertTrue(store.list().isEmpty())
        assertFalse(store.received(id))
        assertFalse(File(dir, "$id.part").exists())
        assertTrue(store.save(id, "image/jpeg", ByteArrayInputStream(byteArrayOf(3))) { _, _ -> })
    }

    @Test fun photoIsInvisibleUntilValidationFinishes() = withStore { store, dir ->
        val id = UUID.randomUUID().toString()
        store.save(id, "image/png", ByteArrayInputStream(byteArrayOf(1, 2))) { file, _ ->
            assertTrue(file.isFile)
            assertTrue(store.list().isEmpty())
            assertFalse(store.received(id))
            assertFalse(File(dir, "$id.png").exists())
        }
        assertEquals("$id.png", store.list().single().name)
    }

    @Test fun publishedPhotoDeduplicatesAfterCrashBeforeReceipt() = withStore { _, dir ->
        val id = UUID.randomUUID().toString()
        val original = byteArrayOf(4, 5, 6)
        File(dir, "$id.webp").writeBytes(original)
        val restored = AtomicPhotoStore(dir)
        assertTrue(restored.received(id))
        assertFalse(restored.save(id, "image/webp", ByteArrayInputStream(byteArrayOf(9))) { _, _ -> fail("Saved photos must not be overwritten") })
        assertArrayEquals(original, restored.list().single().readBytes())
    }

    @Test fun uuidReceiptAndDeletionDoNotScanPhotoCollection() {
        val root = Files.createTempDirectory("gallery-scans").toFile()
        val directory = object : File(root.path) {
            var scans = 0
            override fun listFiles(): Array<File>? { scans++; return super.listFiles() }
        }
        try {
            val store = AtomicPhotoStore(directory)
            directory.scans = 0
            val id = UUID.randomUUID().toString()
            assertFalse(store.received(id))
            assertTrue(store.save(id, "image/jpeg", ByteArrayInputStream(byteArrayOf(1))) { _, _ -> })
            assertTrue(store.received(id))
            assertFalse(store.save(id, "image/jpeg", ByteArrayInputStream(byteArrayOf(9))) { _, _ -> fail("Duplicate must not decode") })
            store.delete(id)
            assertTrue(store.received(id))
            assertEquals(0, directory.scans)
        } finally { root.deleteRecursively() }
    }

    @Test fun legacyFilesInEverySupportedFormatKeepTheirBytesAndDeduplicate() = withStore { store, dir ->
        PhotoProtocol.extensions.forEach { (mime, extension) ->
            val id = UUID.randomUUID().toString()
            val bytes = byteArrayOf(1, 2, 3, extension.length.toByte())
            val existing = File(dir, "$id.$extension").apply { writeBytes(bytes) }
            assertTrue(store.received(id))
            assertFalse(store.save(id, mime, ByteArrayInputStream(byteArrayOf(9))) { _, _ -> fail("Legacy bytes must be retained") })
            assertArrayEquals(bytes, existing.readBytes())
            store.delete(id)
            assertFalse(existing.exists())
            assertTrue(store.received(id))
        }
    }

    @Test fun invalidIdentifiersCannotEscapePrivateStorage() = withStore { store, _ ->
        for (id in listOf("../escape", "1-1-1-1-1", "", "12345678-1234-1234-1234-123456789ABC")) {
            assertFalse(PhotoProtocol.validId(id))
            assertThrows(IllegalArgumentException::class.java) { store.delete(id) }
        }
    }

    @Test fun protocolRecognizesOnlySupportedHeadersAndExactPaths() {
        assertEquals("image/jpeg", PhotoProtocol.detectMime(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertEquals("image/png", PhotoProtocol.detectMime(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        assertEquals("image/webp", PhotoProtocol.detectMime("RIFF1234WEBP".toByteArray()))
        assertNull(PhotoProtocol.detectMime("GIF89a".toByteArray()))
        val id = UUID.randomUUID().toString()
        assertEquals(id, PhotoProtocol.idFromPath(PhotoProtocol.PREFIX + id))
        assertNull(PhotoProtocol.idFromPath(PhotoProtocol.PREFIX + id + "/extra"))
        assertNull(PhotoProtocol.idFromPath("/other/" + id))
        assertThrows(IllegalArgumentException::class.java) { PhotoProtocol.extension("image/gif") }
    }
}
