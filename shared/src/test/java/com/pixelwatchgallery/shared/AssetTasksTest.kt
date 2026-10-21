package com.pixelwatchgallery.shared

import com.google.android.gms.common.api.Releasable
import com.google.android.gms.tasks.TaskCompletionSource
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class AssetTasksTest {
    private class Resource : Releasable {
        var releases = 0
        override fun release() { releases++ }
    }

    @Test fun successfulWaitTransfersOwnershipToCaller() = runBlocking {
        val task = TaskCompletionSource<Resource>()
        val resource = Resource()
        task.setResult(resource)
        assertSame(resource, task.task.awaitReleasable())
        assertEquals(0, resource.releases)
        resource.release()
    }

    @Test fun timedOutWaitReleasesLateAsset() = runBlocking {
        val task = TaskCompletionSource<Resource>()
        assertNull(withTimeoutOrNull(20) { task.task.awaitReleasable() })
        val resource = Resource()
        task.setResult(resource)
        assertEquals(1, resource.releases)
    }

    @Test fun cancellationBeforeDispatchReleasesAsset() = runBlocking {
        val task = TaskCompletionSource<Resource>()
        val resource = Resource()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            task.task.awaitReleasable()
            fail("Cancelled coroutine must not acquire the resource")
        }
        task.setResult(resource)
        waiter.cancel()
        waiter.join()
        assertEquals(1, resource.releases)
    }

    @Test fun failedTaskPropagatesOriginalFailure() = runBlocking {
        val task = TaskCompletionSource<Resource>()
        val error = IOException("Asset unavailable")
        task.setException(error)
        try {
            task.task.awaitReleasable()
            fail("The failure must reach the receiver")
        } catch (actual: IOException) {
            // Coroutine stack-trace recovery can copy an exception while retaining its cause.
            assertEquals(error.message, actual.message)
            assertTrue(actual === error || actual.cause === error)
        }
    }
}
