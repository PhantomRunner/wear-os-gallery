package com.pixelwatchgallery.mobile

import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = TransferRepository.get(applicationContext)
        setContent { MaterialTheme(colorScheme = darkColorScheme(
            primary = Color(0xFFB9F6CA), background = Color(0xFF080C0A), surface = Color(0xFF111A15),
            secondaryContainer = Color(0xFF20392A)
        )) { GalleryScreen(repository) } }
    }
}

@Composable
private fun GalleryScreen(repository: TransferRepository) {
    val photos by repository.photos.collectAsStateWithLifecycle()
    val batch by repository.batch.collectAsStateWithLifecycle()
    val progress = remember(batch) { batch?.progress }
    val context = LocalContext.current
    val busy by repository.busy.collectAsStateWithLifecycle()
    val notice by repository.notice.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { repository.select(it) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); repository.dismissNotice() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val pending = photos.count { it.state == TransferState.IDLE }
                Button(onClick = repository::sendPending, enabled = pending > 0 && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (busy) progress?.phase?.label ?: "Preparing" else "Send to Watch${if (pending > 0) " · $pending" else ""}")
                }
                Text("Only sends when you tap. Original files, no compression.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }) { padding ->
        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("PIXEL WATCH GALLERY", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Text("Your photos.\nOn your wrist.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text("Choose a few favorites to keep on your watch, even when your phone stays behind.", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Select photos") }
                    Text("${photos.count { it.state != TransferState.CONFIRMED }} selected · ${photos.count { it.state == TransferState.CONFIRMED }} confirmed",
                        style = MaterialTheme.typography.titleMedium)
                    progress?.takeIf { it.count > 0 }?.let { transfer ->
                        Text("Sending to Pixel Watch 2", style = MaterialTheme.typography.titleMedium)
                        Text(transfer.phase.label, color = if (transfer.phase == BatchPhase.FAILED)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text(transfer.percent?.let { "$it%" } ?: "Calculating total size…", style = MaterialTheme.typography.headlineSmall)
                        transfer.percent?.let { percentage ->
                            LinearProgressIndicator(progress = { percentage / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                        Text("${Formatter.formatShortFileSize(context, transfer.confirmedBytes)} / ${transfer.totalBytes?.let {
                            Formatter.formatShortFileSize(context, it)
                        } ?: "total unknown"}", style = MaterialTheme.typography.bodyMedium)
                        Text("${transfer.received} / ${transfer.count} photos received", style = MaterialTheme.typography.bodyMedium)
                        if (transfer.failed > 0) Text("${transfer.failed} failed. Retry below. Only watch receipts count as progress.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (photos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Your selection will appear here. JPEG, PNG and WebP are supported.", Modifier.padding(vertical = 28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(photos, key = { it.id }) { photo ->
                Card(shape = RoundedCornerShape(20.dp)) {
                    var previewFailed by remember(photo.uri) { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        AsyncImage(model = photo.uri, contentDescription = photo.name, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)), onError = { previewFailed = true })
                        if (previewFailed) Text("Preview unavailable", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(photo.name, maxLines = 2, style = MaterialTheme.typography.labelLarge)
                        Text(photo.state.label,
                            color = if (photo.state == TransferState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium)
                        Text(photo.detail, style = MaterialTheme.typography.bodySmall)
                        if ((photo.sizeBytes ?: 0) > LARGE_PHOTO_BYTES) Text(
                            "Large original (>5 MB). Sending is allowed; it may take longer.",
                            color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                        if (photo.state == TransferState.FAILED || photo.state == TransferState.QUEUED) {
                            TextButton(onClick = { repository.retry(photo.id) }, enabled = !busy) {
                                Text(if (photo.state == TransferState.QUEUED) "Retry confirmation" else "Retry")
                            }
                        }
                        if (!photo.state.isActive && photo.state != TransferState.QUEUED) {
                            TextButton(onClick = { repository.remove(photo.id) }, enabled = !busy) { Text("Remove") }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Install Pixel Watch Gallery on your paired watch and open it once before sending. Keep Bluetooth enabled while transfers finish.",
                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
