package com.pixelwatchgallery.mobile

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale

@Composable
internal fun GalleryScreen(
    state: PhoneGalleryState,
    onAdd: () -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onNoticeDismiss: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); onNoticeDismiss() }
    }
    val selectedGrid = rememberLazyGridState()
    val sentGrid = rememberLazyGridState()
    LaunchedEffect(state.transferId, state.progress != null) {
        // Inserting a keyed summary otherwise preserves the old thumbnail anchor below it.
        // Move once per batch, never on every receipt or progress update.
        if (state.progress != null) {
            selectedGrid.scrollToItem(0)
            sentGrid.scrollToItem(0)
        }
    }
    val visible = if (selectedTab == 0) state.selected else state.sent
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val compactHeight = windowHeight < 480.dp
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                if (compactHeight) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        GalleryTabs(state, selectedTab, { selectedTab = it }, Modifier.weight(1.5f))
                        FilledTonalIconButton(onClick = onAdd, enabled = !state.busy, modifier = Modifier.size(48.dp).testTag("add-photos")) {
                            GalleryIcon(R.drawable.ic_add, stringResource(R.string.add_photos))
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (LocalDensity.current.fontScale > 1.3f) {
                            FilledTonalIconButton(onClick = onAdd, enabled = !state.busy, modifier = Modifier.size(48.dp).testTag("add-photos")) {
                                GalleryIcon(R.drawable.ic_add, stringResource(R.string.add_photos))
                            }
                        } else {
                            TextButton(onClick = onAdd, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("add-photos")) {
                                GalleryIcon(R.drawable.ic_add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.add_photos))
                            }
                        }
                    }
                    GalleryTabs(state, selectedTab, { selectedTab = it })
                }
            }
        },
        bottomBar = {
            if (selectedTab == 0 && state.showSendBar) SendBar(state, compactHeight, onSend)
        }
    ) { padding ->
        LazyVerticalGrid(columns = GridCells.Adaptive(if (LocalDensity.current.fontScale > 1.3f) 180.dp else 148.dp),
            state = if (selectedTab == 0) selectedGrid else sentGrid,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("photo-grid"),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.progress?.takeIf { it.count > 0 }?.let { progress ->
                item(key = "transfer", span = { GridItemSpan(maxLineSpan) }) { TransferSummary(progress) }
            }
            if (visible.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyGallery(firstLaunch = state.photos.isEmpty(), sentTab = selectedTab == 1,
                        enabled = !state.busy, onAdd = onAdd)
                }
            } else {
                item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(if (selectedTab == 0) R.string.selected_hint else R.string.sent_hint),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                }
                items(visible, key = { it.id }, contentType = { "photo" }) { photo ->
                    PhotoCard(photo, canRetry = state.canRetry(photo),
                        onDetails = { detailId = photo.id }, onRetry = { onRetry(photo.id) })
                }
            }
        }
    }
    // Resolve against live state so a late receipt updates an open sheet too.
    state.photos.firstOrNull { it.id == detailId }?.let { photo ->
        PhotoDetails(photo, state.canRetry(photo), state.canRemove(photo),
            onDismiss = { detailId = null }, onRetry = { onRetry(photo.id); detailId = null },
            onRemove = { onRemove(photo.id); detailId = null })
    }
}

@Composable
private fun GalleryTabs(state: PhoneGalleryState, selectedTab: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    TabRow(selectedTabIndex = selectedTab, modifier = modifier, containerColor = MaterialTheme.colorScheme.background,
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }) {
        Tab(selected = selectedTab == 0, onClick = { onSelect(0) }, modifier = Modifier.testTag("tab-selected"),
            text = { Text(stringResource(R.string.tab_selected, state.selected.size)) })
        Tab(selected = selectedTab == 1, onClick = { onSelect(1) }, modifier = Modifier.testTag("tab-sent"),
            text = { Text(stringResource(R.string.tab_sent, state.sent.size)) })
    }
}

@Composable
private fun SendBar(state: PhoneGalleryState, compact: Boolean, onSend: () -> Unit) {
    val insets = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(
        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(horizontal = 20.dp, vertical = 12.dp)
    val caption = stringResource(if (state.busy || (state.awaitingReceipt && state.readyCount == 0)) R.string.receipt_hint
        else R.string.original_quality)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        if (compact) {
            Row(insets, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(caption, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SendButton(state, onSend, Modifier.weight(1f))
            }
        } else {
            Column(insets, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SendButton(state, onSend, Modifier.fillMaxWidth())
                Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SendButton(state: PhoneGalleryState, onSend: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onSend, enabled = !state.busy && state.readyCount > 0,
        modifier = modifier.heightIn(min = 56.dp).testTag("send-button")) {
        if (!state.busy && state.readyCount > 0) {
            GalleryIcon(R.drawable.ic_upload, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.send_count, state.readyCount), textAlign = TextAlign.Center)
        } else Text(stringResource(if (state.busy) phaseLabel(state.progress?.phase ?: BatchPhase.PREPARING)
            else R.string.status_queued), textAlign = TextAlign.Center)
    }
}

@Composable
private fun TransferSummary(progress: TransferProgress) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val failed = progress.failed > 0
    val complete = progress.phase == BatchPhase.COMPLETED
    val accent = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("transfer-summary")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(accent.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                    GalleryIcon(if (complete) R.drawable.ic_check else if (failed) R.drawable.ic_error else R.drawable.ic_watch, null, tint = accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.transfer_title), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(phaseLabel(progress.phase)), style = MaterialTheme.typography.titleSmall,
                        color = accent, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(48.dp)) {
                    GalleryIcon(R.drawable.ic_expand, stringResource(if (expanded) R.string.hide_transfer_details else R.string.show_transfer_details),
                        Modifier.size(20.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f })
                }
            }
            if (progress.percent == null && !complete && progress.phase != BatchPhase.FAILED) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("transfer-indeterminate"), color = accent)
            } else if (progress.percent != null) {
                LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = accent)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.received_count, progress.count, progress.received, progress.count), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                progress.percent?.let { Text(stringResource(R.string.received_percent, it),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (failed) Text(pluralStringResource(R.plurals.failed_count, progress.failed, progress.failed), style = MaterialTheme.typography.bodySmall, color = accent)
            if (expanded) {
                val context = LocalContext.current
                Text(progress.totalBytes?.let { stringResource(R.string.received_bytes,
                    Formatter.formatShortFileSize(context, progress.confirmedBytes), Formatter.formatShortFileSize(context, it)) }
                    ?: stringResource(R.string.unknown_total), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.receipt_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyGallery(firstLaunch: Boolean, sentTab: Boolean, enabled: Boolean, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center) {
            GalleryIcon(if (sentTab) R.drawable.ic_check else R.drawable.ic_watch, null,
                Modifier.size(42.dp), MaterialTheme.colorScheme.primary)
        }
        val title = if (sentTab) R.string.empty_sent_title else if (firstLaunch) R.string.empty_first_title else R.string.empty_selected_title
        val body = if (sentTab) R.string.empty_sent_body else if (firstLaunch) R.string.empty_first_body else R.string.empty_selected_body
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!sentTab) {
            Button(onClick = onAdd, enabled = enabled, modifier = Modifier.heightIn(min = 52.dp).testTag("empty-add")) {
                GalleryIcon(R.drawable.ic_add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_photos))
            }
            Text(stringResource(R.string.supported_formats), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (firstLaunch) Text(stringResource(R.string.setup_hint), Modifier.padding(top = 18.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PhotoCard(photo: TransferPhoto, canRetry: Boolean, onDetails: () -> Unit, onRetry: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.testTag("photo-${photo.id}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            PhotoPreview(photo)
            FilledIconButton(onClick = onDetails, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xE619211C), contentColor = Color.White)) {
                GalleryIcon(R.drawable.ic_more, stringResource(R.string.photo_options, photo.name))
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(photo.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(formatSize(photo.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PhotoStatus(photo.state)
            if (photo.state == TransferState.FAILED) {
                val retryDescription = stringResource(R.string.retry_photo, photo.name)
                TextButton(onClick = onRetry, enabled = canRetry, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("retry-${photo.id}").semantics { contentDescription = retryDescription }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    GalleryIcon(R.drawable.ic_refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun PhotoPreview(photo: TransferPhoto) {
    val context = LocalContext.current
    var failed by remember(photo.uri) { mutableStateOf(false) }
    val request = remember(context, photo.uri) {
        ImageRequest.Builder(context).data(photo.uri).precision(Precision.EXACT).scale(Scale.FIT).crossfade(false).build()
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(), onError = { failed = true }, onSuccess = { failed = false })
        if (failed) Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryIcon(R.drawable.ic_photo, null, Modifier.size(32.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.preview_unavailable), style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhotoStatus(state: TransferState) {
    val color = when (state) {
        TransferState.FAILED -> MaterialTheme.colorScheme.error
        TransferState.CONFIRMED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        GalleryIcon(when (state) {
            TransferState.FAILED -> R.drawable.ic_error
            TransferState.CONFIRMED -> R.drawable.ic_check
            TransferState.IDLE -> R.drawable.ic_upload
            else -> R.drawable.ic_watch
        }, null, Modifier.size(16.dp), color)
        Text(stringResource(statusLabel(state)), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoDetails(photo: TransferPhoto, canRetry: Boolean, canRemove: Boolean,
    onDismiss: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    var technical by rememberSaveable(photo.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        // This Material version chooses dialog system bars from the device theme.
        // Keep them legible when the device uses light mode and our app stays dark.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().testTag("photo-details"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.photo_details), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        GalleryIcon(R.drawable.ic_close, stringResource(R.string.close_details))
                    }
                }
            }
            item { Text(photo.name, style = MaterialTheme.typography.titleMedium) }
            item { PhotoStatus(photo.state) }
            item { Text(formatSize(photo.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (photo.state == TransferState.FAILED) item { Text(photo.detail, color = MaterialTheme.colorScheme.error) }
            if (photo.state == TransferState.QUEUED) item { Text(stringResource(R.string.receipt_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if ((photo.sizeBytes ?: 0) > LARGE_PHOTO_BYTES) item {
                Text(stringResource(R.string.large_original), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            if (photo.state != TransferState.FAILED) item {
                TextButton(onClick = { technical = !technical }) { Text(stringResource(R.string.technical_details)) }
                if (technical) Text(photo.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (photo.state == TransferState.FAILED || photo.state == TransferState.QUEUED) item {
                Button(onClick = onRetry, enabled = canRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("detail-retry")) {
                    Text(stringResource(if (photo.state == TransferState.QUEUED) R.string.retry_confirmation else R.string.retry))
                }
            }
            item {
                OutlinedButton(onClick = onRemove, enabled = canRemove, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("detail-remove")) {
                    Text(stringResource(if (photo.state == TransferState.CONFIRMED) R.string.remove_history else R.string.remove_selection))
                }
            }
            item { Text(stringResource(R.string.remove_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun formatSize(bytes: Long?): String = bytes?.let { Formatter.formatShortFileSize(LocalContext.current, it) }
    ?: stringResource(R.string.size_unknown)

@Composable
private fun GalleryIcon(@DrawableRes icon: Int, description: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) = Icon(painterResource(icon), description, modifier, tint)

@StringRes internal fun statusLabel(state: TransferState): Int = when (state) {
    TransferState.IDLE -> R.string.status_ready
    TransferState.PREPARING -> R.string.status_preparing
    TransferState.WAITING_FOR_WATCH -> R.string.status_finding_watch
    TransferState.SENDING -> R.string.status_sending
    TransferState.QUEUED -> R.string.status_queued
    TransferState.CONFIRMED -> R.string.status_confirmed
    TransferState.FAILED -> R.string.status_failed
}

@StringRes internal fun phaseLabel(phase: BatchPhase): Int = when (phase) {
    BatchPhase.IDLE -> R.string.phase_idle
    BatchPhase.PREPARING -> R.string.status_preparing
    BatchPhase.WAITING_FOR_WATCH -> R.string.status_finding_watch
    BatchPhase.SENDING -> R.string.status_sending
    BatchPhase.WAITING_FOR_CONFIRMATION -> R.string.status_queued
    BatchPhase.COMPLETED -> R.string.phase_complete
    BatchPhase.FAILED -> R.string.phase_failed
}
