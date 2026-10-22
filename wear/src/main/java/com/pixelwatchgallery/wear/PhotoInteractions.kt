package com.pixelwatchgallery.wear

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

internal const val MAX_PHOTO_ZOOM = 3f
internal const val DOUBLE_TAP_ZOOM = 2.5f

internal fun wrappedPhotoIndex(index: Int, steps: Int, count: Int): Int {
    if (count <= 1) return 0
    return Math.floorMod(index.toLong() + steps, count.toLong()).toInt()
}

/** Accumulate high-resolution crown deltas without queuing animations or reacting to noise. */
internal class CrownPhotoStepper(private val threshold: Float) {
    private var remainder = 0f
    private var lastEvent: Long? = null

    init { require(threshold.isFinite() && threshold > 0f) }

    fun steps(delta: Float, timeMillis: Long): Int {
        if (!delta.isFinite() || delta == 0f) return 0
        val previous = lastEvent
        if (previous == null || timeMillis - previous > 300 || timeMillis < previous || remainder * delta < 0) remainder = 0f
        lastEvent = timeMillis
        remainder += delta
        val steps = (remainder / threshold).toInt()
        remainder -= steps * threshold
        return steps
    }
}

internal fun boundedPhotoPan(offset: Offset, scale: Float, viewport: IntSize, image: Size): Offset {
    if (scale <= 1f || viewport.width == 0 || viewport.height == 0) return Offset.Zero
    val width = if (image.width > 0) image.width else viewport.width.toFloat()
    val height = if (image.height > 0) image.height else viewport.height.toFloat()
    val fit = minOf(viewport.width / width, viewport.height / height)
    val maxX = ((width * fit * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((height * fit * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

@Stable
internal class PhotoZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewport = IntSize.Zero
    private var image = Size.Zero
    val isZoomed: Boolean get() = scale > 1.01f

    fun updateViewport(size: IntSize) { viewport = size; clamp() }
    fun updateImage(size: Size) { image = size; clamp() }

    fun transform(zoomChange: Float, pan: Offset) {
        if (!zoomChange.isFinite() || zoomChange <= 0 || !pan.x.isFinite() || !pan.y.isFinite()) return
        val next = (scale * zoomChange).coerceIn(1f, MAX_PHOTO_ZOOM)
        offset = boundedPhotoPan(offset * (next / scale) + pan, next, viewport, image)
        scale = if (abs(next - 1f) < 0.01f) 1f else next
        if (!isZoomed) reset()
    }

    fun toggle(at: Offset = Offset(viewport.width / 2f, viewport.height / 2f)) {
        if (isZoomed) reset() else {
            scale = DOUBLE_TAP_ZOOM
            val center = Offset(viewport.width / 2f, viewport.height / 2f)
            offset = boundedPhotoPan((center - at) * (scale - 1f), scale, viewport, image)
        }
    }

    private fun clamp() { offset = boundedPhotoPan(offset, scale, viewport, image) }
    private fun reset() { scale = 1f; offset = Offset.Zero }
}
