package com.pixelwatchgallery.wear

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

@Stable
internal class ViewerControls {
    var counterVisible by mutableStateOf(true)
        private set
    var actionsVisible by mutableStateOf(false)
        private set
    var revision by mutableIntStateOf(0)
        private set

    fun tap() {
        actionsVisible = !actionsVisible
        counterVisible = actionsVisible
        revision++
    }

    fun expire() { counterVisible = false; actionsVisible = false }
    fun opened() { counterVisible = true; actionsVisible = false; revision++ }
}

@Composable
internal fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        enabled = manager?.isTouchExplorationEnabled == true
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

@Composable
internal fun AutoHideControls(controls: ViewerControls, touching: Boolean, dialogOpen: Boolean,
    touchExploration: Boolean, active: Boolean) {
    LaunchedEffect(controls, controls.revision, touching, dialogOpen, touchExploration, active) {
        if (!touching && !dialogOpen && !touchExploration && active) {
            delay(3_000)
            controls.expire()
        }
    }
}

/** Only the crown's completed page change calls this; no feedback for touch or restoration. */
internal class CrownFeedbackGate {
    private var lastTick: Long? = null
    fun request(previousId: String?, nextId: String?, timeMillis: Long): Boolean {
        if (previousId == null || nextId == null || previousId == nextId) return false
        val previous = lastTick
        if (previous != null && timeMillis >= previous && timeMillis - previous < 50) return false
        lastTick = timeMillis
        return true
    }
}
