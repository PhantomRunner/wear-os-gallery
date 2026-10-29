package com.pixelwatchgallery.wear

internal data class BatchDeletionResult(val deleted: Set<String>, val failures: Map<String, String>)

internal data class BatchDeletion(
    val ids: Set<String> = emptySet(),
    val completed: Int = 0,
    val running: Boolean = false,
    val result: BatchDeletionResult? = null
)
