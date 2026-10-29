package com.pixelwatchgallery.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*
import java.io.File

@Composable
internal fun SelectionGallery(
    photos: List<File>, position: WatchPosition, deletion: BatchDeletion,
    onGridPosition: (String?, Int) -> Unit, onDelete: (Set<String>) -> Unit,
    onResultHandled: () -> Unit, onDone: () -> Unit
) {
    var selected by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var pending by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var failed by rememberSaveable { mutableIntStateOf(0) }
    val ids = photos.map { it.nameWithoutExtension }.toSet()
    val selection = selected.toSet().intersect(ids)
    LaunchedEffect(ids) {
        selected = selection.toList()
        if (ids.isEmpty() && !deletion.running && deletion.result == null) onDone()
    }
    LaunchedEffect(deletion.result) {
        deletion.result?.let { result ->
            pending = null
            if (result.failures.isEmpty()) {
                onResultHandled(); onDone()
            } else {
                selected = result.failures.keys.intersect(ids).toList()
                failed = result.failures.size
                onResultHandled()
            }
        }
    }
    // The nav host handles ordinary Back/edge dismissal, returning to the grid underneath.
    BackHandler(enabled = deletion.running) {}
    GalleryGrid(photos, null, position, onGridPosition,
        onOpen = { photo ->
            val id = photo.nameWithoutExtension
            selected = (if (id in selection) selection - id else selection + id).toList()
        }, selectedIds = selection, enabled = !deletion.running && pending == null,
        headerAction = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (failed > 0) Text(pluralStringResource(R.plurals.batch_delete_failed, failed, failed),
                    color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("batch-delete-error"))
                TextButton(enabled = !deletion.running && pending == null,
                    onClick = { selected = (if (selection == ids) emptySet() else ids).toList() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("select-all")) {
                    Text(stringResource(if (selection == ids) R.string.deselect_all else R.string.select_all))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(enabled = selection.isNotEmpty() && !deletion.running && pending == null,
                        onClick = { pending = selection.toList() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("delete-selected")) {
                        Text(stringResource(R.string.delete_count, selection.size))
                    }
                    TextButton(enabled = !deletion.running, onClick = onDone,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("cancel-selection")) {
                        Text(stringResource(R.string.cancel_selection))
                    }
                }
            }
        })
    AlertDialog(visible = pending != null || deletion.running,
        onDismissRequest = { if (!deletion.running) pending = null },
        title = { val count = pending?.size ?: deletion.ids.size
            Text(pluralStringResource(R.plurals.delete_photos_title, count, count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_photos_explanation))
                if (deletion.running) Text(stringResource(R.string.deleting_progress, deletion.completed, deletion.ids.size),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("deleting-photos"))
            }
        }, confirmButton = {
            if (!deletion.running) AlertDialogDefaults.ConfirmButton(
                modifier = Modifier.testTag("confirm-delete-selected"),
                onClick = { if (!deletion.running) pending?.let { onDelete(it.toSet()) } })
        }, dismissButton = {
            if (!deletion.running) AlertDialogDefaults.DismissButton(
                modifier = Modifier.testTag("cancel-delete-selected"), onClick = { if (!deletion.running) pending = null })
        })
}
