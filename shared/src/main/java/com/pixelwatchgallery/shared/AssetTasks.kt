package com.pixelwatchgallery.shared

import com.google.android.gms.common.api.Releasable
import com.google.android.gms.tasks.Task
import java.util.concurrent.Executor
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Unlike ordinary Task.await(), release a late resource if the waiter has timed out. */
suspend fun <T : Releasable> Task<T>.awaitReleasable(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(Executor { it.run() }) { completed ->
        val error = completed.exception
        when {
            completed.isCanceled -> continuation.cancel()
            error != null -> continuation.resumeWithException(error)
            else -> continuation.resume(completed.result) { _, resource, _ -> resource.release() }
        }
    }
}
