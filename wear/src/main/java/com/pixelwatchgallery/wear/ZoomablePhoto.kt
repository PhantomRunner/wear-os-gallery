package com.pixelwatchgallery.wear

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun ZoomablePhoto(file: File, zoom: PhotoZoomState) {
    val zoomed by remember(zoom) { derivedStateOf { zoom.isZoomed } }
    var allowPan by remember { mutableStateOf(true) }
    val transform = rememberTransformableState { scale, pan, _ -> zoom.transform(scale, pan) }
    Box(Modifier.fillMaxSize().clipToBounds().testTag("zoomable-photo")
        // Follow the pager's active page so rotary focus survives swipes and zoom changes.
        .requestFocusOnHierarchyActive().focusable()
        .onSizeChanged(zoom::updateViewport)
        .semantics {
            stateDescription = "Zoom ${(zoom.scale * 100).roundToInt()}%"
            onClick(label = "Toggle photo zoom") { zoom.toggle(); true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Let the normal Wear edge Back gesture reach the navigation host while zoomed.
                allowPan = down.position.x > size.width * 0.15f
                do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom.toggle(it) }) }
        .transformable(transform, canPan = { zoom.isZoomed && allowPan }, lockRotationOnZoomPan = true)) {
        LocalPhoto(file, Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom.scale; scaleY = zoom.scale
            translationX = zoom.offset.x; translationY = zoom.offset.y
        }, ContentScale.Fit, detailed = zoomed, onImageSize = zoom::updateImage)
    }
}
