package com.pixelwatchgallery.wear

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit second-stage check after screenshots preserve their fixture and ADB kills the process. */
@RunWith(AndroidJUnit4::class)
class ColdStartVerificationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun coldStartRestoresViewerOrFallsBackWhenItsPhotoWasDeleted() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verify-cold-start") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureFile = File(context.getExternalFilesDir(null), "cold-start-fixture.json")
        val fixture = JSONObject(fixtureFile.readText())
        val repository = PhotoRepository.get(context)
        compose.waitUntil(10_000) { repository.state.value.loaded }
        if (args.getString("missing-photo") == "true") {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("gallery-grid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("photo-viewer").assertDoesNotExist()
            check(repository.state.value.photos.size == 2)
        } else {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("photo-viewer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("photo-viewer").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2 / 3"))
            compose.onNodeWithTag("zoomable-photo").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 100%"))
            compose.onNodeWithTag("delete-photo").assertDoesNotExist()
            check(WatchPositionStore.get(context).state.value.position.photoId == fixture.getString("selected"))
        }
        if (args.getString("preserve-fixture") != "true") runBlocking {
            val ids = fixture.getJSONArray("ids")
            repeat(ids.length()) { repository.delete(ids.getString(it)) }
            WatchPositionStore.get(context).showGallery()
            WatchPositionStore.get(context).awaitSaved()
            fixtureFile.delete()
        }
    }
}
