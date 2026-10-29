package com.pixelwatchgallery.mobile

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pixelwatchgallery.shared.PhotoProtocol
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryPersistenceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(base.cacheDir, "history-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }
    private val journal get() = File(directory, "transfers.json")
    @After fun cleanup() { directory.deleteRecursively() }

    private fun fixture(states: List<TransferState>): Pair<TransferRepository, List<String>> {
        val ids = states.map { UUID.randomUUID().toString() }
        val photos = JSONArray()
        val batch = JSONArray()
        states.forEachIndexed { index, state ->
            photos.put(JSONObject().put("id", ids[index]).put("uri", "content://test/$index").put("name", "$index.jpg")
                .put("state", state.name).put("detail", "fixture").put("node", "watch").put("sizeBytes", 100))
            batch.put(JSONObject().put("id", ids[index]).put("state", state.name).put("sizeBytes", 100))
        }
        journal.writeText(JSONObject().put("photos", photos).put("batch", JSONObject().put("id", "batch").put("photos", batch)).toString())
        return TransferRepository(context) to ids
    }

    @Test fun clearPersistsAndKeepsPendingRowsAndMixedBatch() = runBlocking {
        val (repository, ids) = fixture(listOf(TransferState.CONFIRMED, TransferState.QUEUED, TransferState.FAILED, TransferState.IDLE))
        assertTrue(repository.clearHistory(ids.toSet()))
        assertEquals(ids.drop(1), repository.photos.value.map { it.id })
        assertEquals(1, repository.batch.value!!.progress.received)
        val reloaded = TransferRepository(context)
        val rows = withTimeout(5000) { reloaded.photos.first { it.size == 3 } }
        assertEquals(ids.drop(1), rows.map { it.id })
        assertEquals(3, JSONObject(journal.readText()).getJSONArray("photos").length())
    }

    @Test fun lateReceiptIsKeptAndRemovedRowsAreNotResurrectedByAck() = runBlocking {
        val (repository, ids) = fixture(listOf(TransferState.CONFIRMED, TransferState.QUEUED))
        withTimeout(5000) { repository.photos.first { it.size == 2 } }
        suspend fun ack(id: String) { repository.acknowledge("watch", JSONObject().put(PhotoProtocol.ID, id).put("ok", true).toString().toByteArray()) }
        ack(ids[1])
        assertTrue(repository.clearHistory(setOf(ids[0])))
        ack(ids[0])
        assertEquals(listOf(ids[1]), repository.photos.value.map { it.id })
        assertTrue(repository.clearHistory(setOf(ids[1])))
        assertTrue(repository.photos.value.isEmpty())
        assertNull(repository.batch.value)
        assertTrue(JSONObject(journal.readText()).isNull("batch"))
    }

    @Test fun failedJournalWriteLeavesHistoryUnchangedAndRetryWorks() = runBlocking {
        val (repository, ids) = fixture(listOf(TransferState.CONFIRMED))
        withTimeout(5000) { repository.photos.first { it.size == 1 } }
        val blocker = File(directory, "transfers.json.new").apply { mkdirs() }
        File(blocker, "block").writeText("test")
        assertFalse(repository.clearHistory(ids.toSet()))
        assertEquals(ids, repository.photos.value.map { it.id })
        assertEquals(1, JSONObject(journal.readText()).getJSONArray("photos").length())
        blocker.deleteRecursively()
        assertTrue(repository.clearHistory(ids.toSet()))
        assertTrue(repository.photos.value.isEmpty())
    }
}
