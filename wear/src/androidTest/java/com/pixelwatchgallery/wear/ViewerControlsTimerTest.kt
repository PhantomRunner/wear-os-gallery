package com.pixelwatchgallery.wear

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerControlsTimerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun timerExpiresAfterThreeSecondsAndTapRestartsIt() {
        val controls = ViewerControls()
        compose.mainClock.autoAdvance = false
        compose.setContent { AutoHideControls(controls, false, false, false, true) }
        compose.mainClock.advanceTimeBy(2_500)
        assertTrue(controls.counterVisible)
        compose.runOnIdle { controls.tap() }
        compose.mainClock.advanceTimeBy(2_500)
        assertTrue(controls.actionsVisible)
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertFalse(controls.actionsVisible)
            assertFalse(controls.counterVisible)
        }
    }

    @Test fun holdingAndDeleteDialogPauseTheTimer() {
        val controls = ViewerControls().apply { tap() }
        val touching = mutableStateOf(true)
        val dialog = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { AutoHideControls(controls, touching.value, dialog.value, false, true) }
        compose.mainClock.advanceTimeBy(5_000)
        assertTrue(controls.actionsVisible)
        compose.runOnIdle { touching.value = false; dialog.value = true }
        compose.mainClock.advanceTimeBy(5_000)
        assertTrue(controls.actionsVisible)
        compose.runOnIdle { dialog.value = false }
        compose.mainClock.advanceTimeBy(2_500)
        assertTrue(controls.actionsVisible)
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertFalse(controls.actionsVisible) }
    }

    @Test fun touchExplorationAndBackgroundDisableExpiry() {
        val controls = ViewerControls().apply { tap() }
        val exploration = mutableStateOf(true)
        val active = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { AutoHideControls(controls, false, false, exploration.value, active.value) }
        compose.mainClock.advanceTimeBy(10_000)
        assertTrue(controls.actionsVisible)
        compose.runOnIdle { exploration.value = false; active.value = false }
        compose.mainClock.advanceTimeBy(10_000)
        assertTrue(controls.actionsVisible)
        compose.runOnIdle { active.value = true }
        compose.mainClock.advanceTimeBy(3_200)
        compose.runOnIdle { assertFalse(controls.actionsVisible) }
    }
}
