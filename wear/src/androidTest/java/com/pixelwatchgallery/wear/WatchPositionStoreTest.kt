package com.pixelwatchgallery.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchPositionStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun snapshotRoundTripsAndOlderGridWritesCannotOverwriteViewer() = runBlocking {
        val name = "position-test-${UUID.randomUUID()}"
        val photo = UUID.randomUUID().toString()
        val anchor = UUID.randomUUID().toString()
        try {
            WatchPositionStore(context, name).use { first ->
                first.awaitSaved()
                first.saveGrid(anchor, 41)
                first.openPhoto(photo)
                first.flush()
                first.awaitSaved()
            }
            WatchPositionStore(context, name).use { second ->
                second.awaitSaved()
                assertEquals(WatchPosition(GalleryDestination.VIEWER, photo, anchor, 41), second.state.value.position)
                second.showGallery()
                second.awaitSaved()
            }
            WatchPositionStore(context, name).use { third ->
                third.awaitSaved()
                assertEquals(GalleryDestination.GALLERY, third.state.value.position.destination)
                assertEquals(anchor, third.state.value.position.gridAnchorId)
            }
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun absentMalformedAndInvalidSnapshotsRecoverSafely() = runBlocking {
        assertEquals(WatchPosition(), WatchPositionStore.decode(null))
        for (value in listOf("{", "{}", "[]", "{\"screen\":\"UNKNOWN\"}",
            "{\"screen\":\"VIEWER\",\"photo\":\"invalid\",\"anchor\":null,\"offset\":0}")) {
            assertEquals(WatchPosition(), WatchPositionStore.decode(value))
        }
        val name = "position-test-${UUID.randomUUID()}"
        try {
            context.getSharedPreferences(name, 0).edit().putString("snapshot", "broken").commit()
            WatchPositionStore(context, name).use { recovered ->
                recovered.awaitSaved()
                assertTrue(recovered.state.value.loaded)
                assertEquals(WatchPosition(), recovered.state.value.position)
            }
        } finally { context.deleteSharedPreferences(name) }
    }
}
