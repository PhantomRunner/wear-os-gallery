package com.pixelwatchgallery.wear

import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.GestureInclusion
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerDefaults
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.*
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

private val selectionProcess = UUID.randomUUID().toString()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = PhotoRepository.get(applicationContext)
        setContent { MaterialTheme { WatchGallery(repository, lifecycle) } }
    }
}

@Composable
private fun WatchGallery(repository: PhotoRepository, lifecycle: Lifecycle) {
    // Read the State holders inside retained navigation destinations (including restored ones).
    val galleryState = repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val positions = remember(context) { WatchPositionStore.get(context) }
    val positionState = positions.state.collectAsStateWithLifecycle()
    val deletionState = repository.deletion.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runCatching { repository.refresh() }.onSuccess { error = null }
                .onFailure {
                    if (it is CancellationException) throw it
                    error = it.message ?: "Cannot open photo storage."
                }
        }
    }
    DisposableEffect(lifecycle, positions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) positions.flush()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); positions.flush() }
    }
    val nav = rememberSwipeDismissableNavController()
    val entry by nav.currentBackStackEntryAsState()
    var selectedId by rememberSaveable { mutableStateOf("") }
    var restored by remember { mutableStateOf(false) }
    var savedSelectionProcess by rememberSaveable { mutableStateOf(selectionProcess) }
    val loaded = galleryState.value.loaded && positionState.value.loaded
    LaunchedEffect(loaded) {
        if (!loaded || restored) return@LaunchedEffect
        if (savedSelectionProcess != selectionProcess) {
            if (nav.currentBackStackEntry?.destination?.route == "select") nav.popBackStack()
            savedSelectionProcess = selectionProcess
        }
        val id = positionState.value.position.resumePhoto(galleryState.value.photos.map { it.nameWithoutExtension })
        if (id != null) {
            selectedId = id
            if (nav.currentBackStackEntry?.destination?.route != "viewer") nav.navigate("viewer") { launchSingleTop = true }
        } else {
            if (nav.currentBackStackEntry?.destination?.route == "viewer") nav.popBackStack()
            positions.showGallery()
        }
        restored = true
    }
    LaunchedEffect(entry, restored) {
        if (restored && entry?.destination?.route == "gallery" && nav.currentBackStackEntry?.destination?.route == "gallery") {
            positions.showGallery()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SwipeDismissableNavHost(navController = nav, startDestination = "gallery",
            userSwipeEnabled = !deletionState.value.running) {
            composable("gallery") {
                val gallery by galleryState
                val position by positionState
                if (restored) GalleryGrid(gallery.photos, error, position.position,
                    onGridPosition = positions::saveGrid,
                    headerAction = {
                        TextButton(onClick = { nav.navigate("select") { launchSingleTop = true } },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("select-photos")) { Text(stringResource(R.string.select_photos)) }
                    },
                    onOpen = { file ->
                        selectedId = file.nameWithoutExtension
                        positions.openPhoto(selectedId)
                        nav.navigate("viewer")
                    })
            }
            composable("select") {
                val gallery by galleryState
                val position by positionState
                val deletion by deletionState
                if (restored) SelectionGallery(gallery.photos, position.position, deletion,
                    positions::saveGrid, repository::startDeletion, repository::dismissDeletionResult,
                    onDone = { nav.popBackStack() })
            }
            composable("viewer") {
                val gallery by galleryState
                if (restored) PhotoViewer(gallery.photos, selectedId, lifecycle,
                    onPhotoSelected = { selectedId = it; positions.openPhoto(it) },
                    onEmpty = { nav.popBackStack() }, onDelete = { repository.delete(it) })
            }
        }
        if (!restored) Text("Gallery", Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun GalleryGrid(photos: List<File>, error: String?, position: WatchPosition,
    onGridPosition: (String?, Int) -> Unit, onOpen: (File) -> Unit,
    headerAction: (@Composable () -> Unit)? = null, selectedIds: Set<String>? = null, enabled: Boolean = true) {
    if (photos.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Gallery", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Text(error ?: "No photos yet", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Select photos on your phone, then tap Send to Watch.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    val initialPosition = remember { position }
    val ids = photos.map { it.nameWithoutExtension }
    val state = rememberLazyGridState(initialPosition.gridIndex(ids), initialPosition.gridOffset(ids))
    val currentPhotos by rememberUpdatedState(photos)
    val savePosition by rememberUpdatedState(onGridPosition)
    LaunchedEffect(state) {
        val initialIds = currentPhotos.map { it.nameWithoutExtension }
        // Override an index-only Compose restoration with our stable photo anchor.
        state.scrollToItem(initialPosition.gridIndex(initialIds), initialPosition.gridOffset(initialIds))
        snapshotFlow {
            val index = state.firstVisibleItemIndex
            val anchor = if (index == 0) null else {
                val key = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key
                currentPhotos.firstOrNull { it.name == key }?.nameWithoutExtension ?: return@snapshotFlow null
            }
            anchor to state.firstVisibleItemScrollOffset
        }.filterNotNull().distinctUntilChanged().collect { (anchor, offset) -> savePosition(anchor, offset) }
    }
    val scope = rememberCoroutineScope()
    LazyVerticalGrid(columns = GridCells.Fixed(2), state = state,
        modifier = Modifier.fillMaxSize().testTag("gallery-grid").requestFocusOnHierarchyActive().onRotaryScrollEvent {
            scope.launch { state.scrollBy(it.verticalScrollPixels) }; true
        }.focusable(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(2) }, contentType = "header") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (selectedIds == null) "${photos.size} ${if (photos.size == 1) "photo" else "photos"}"
                    else pluralStringResource(R.plurals.selected_count, selectedIds.size, selectedIds.size), Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                headerAction?.invoke()
            }
        }
        itemsIndexed(photos, key = { _, photo -> photo.name }, contentType = { _, _ -> "photo" }) { index, photo ->
            val selected = selectedIds?.contains(photo.nameWithoutExtension) == true
            val description = stringResource(R.string.selection_photo_description, index + 1, photos.size)
            val action = if (selectedIds == null) Modifier.clickable(enabled = enabled) { onOpen(photo) }
                else Modifier.semantics { contentDescription = description }
                    .toggleable(selected, enabled = enabled, role = Role.Checkbox) { onOpen(photo) }
            Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("grid-photo-${photo.nameWithoutExtension}").clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
                .then(action)) {
                LocalPhoto(photo, Modifier.fillMaxSize(), ContentScale.Crop)
                if (selected) Text("✓", Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(horizontal = 5.dp).clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@OptIn(ExperimentalWearFoundationApi::class)
@Composable
private fun PhotoViewer(photos: List<File>, initialId: String, lifecycle: Lifecycle,
    onPhotoSelected: (String) -> Unit, onEmpty: () -> Unit, onDelete: suspend (String) -> Unit) {
    val initialPage = remember { photos.indexOfFirst { it.nameWithoutExtension == initialId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { photos.size })
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(pager) {
        // Pager's saver restores an index; UUID restoration must win when new photos shifted it.
        if (photos.isNotEmpty()) pager.scrollToPage(initialPage)
        positioned = true
    }
    val currentPhoto = photos.getOrNull(pager.currentPage)?.name
    val zoom = remember(currentPhoto) { PhotoZoomState() }
    val controls = remember(currentPhoto) { ViewerControls() }
    var touching by remember { mutableStateOf(false) }
    val touchExploration = rememberTouchExploration()
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle, controls) {
        val observer = LifecycleEventObserver { _, event ->
            active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (event == Lifecycle.Event.ON_START) controls.opened()
            if (event == Lifecycle.Event.ON_STOP) controls.expire()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val zoomed by remember(zoom) { derivedStateOf { zoom.isZoomed } }
    val crownThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    val crown = remember(crownThreshold) { CrownPhotoStepper(crownThreshold) }
    var crownTarget by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val view = LocalView.current
    val feedback = remember { CrownFeedbackGate() }
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val gestures = remember(accessibility) {
        object : GestureInclusion {
            override fun ignoreGestureStart(offset: Offset, layoutCoordinates: LayoutCoordinates): Boolean =
                accessibility?.isTouchExplorationEnabled != true &&
                    offset.x <= layoutCoordinates.size.width * PagerDefaults.LeftEdgeZoneFraction
        }
    }
    val scope = rememberCoroutineScope()
    var deleteId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AutoHideControls(controls, touching, deleteId != null, touchExploration, active)
    val notifySelection by rememberUpdatedState(onPhotoSelected)
    LaunchedEffect(pager, photos) {
        snapshotFlow {
            if (positioned && !pager.isScrollInProgress) photos.getOrNull(pager.settledPage)?.nameWithoutExtension else null
        }.filterNotNull().distinctUntilChanged().collect { notifySelection(it) }
    }
    LaunchedEffect(crownTarget) {
        val target = crownTarget ?: return@LaunchedEffect
        val previousId = photos.getOrNull(pager.currentPage)?.name
        if (photos.isNotEmpty()) pager.scrollToPage(target.coerceIn(0, photos.lastIndex))
        val nextId = photos.getOrNull(pager.currentPage)?.name
        if (feedback.request(previousId, nextId, SystemClock.uptimeMillis())) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
        }
        if (crownTarget == target) crownTarget = null
    }
    LaunchedEffect(photos.size) {
        if (photos.isEmpty()) onEmpty()
        else if (pager.currentPage >= photos.size) pager.scrollToPage(photos.lastIndex)
    }
    if (photos.isEmpty()) return
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("photo-viewer")
        .semantics { stateDescription = "${(pager.currentPage + 1).coerceAtMost(photos.size)} / ${photos.size}" }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touching = true
                try {
                    do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                    while (event.changes.any { it.pressed })
                } finally { touching = false }
            }
        }
        .onPreRotaryScrollEvent { event ->
            if (deleteId == null && photos.size > 1) {
                val steps = crown.steps(event.verticalScrollPixels, event.uptimeMillis)
                if (steps != 0) crownTarget = wrappedPhotoIndex(crownTarget ?: pager.currentPage, steps, photos.size)
            }
            true
        }) {
        // Reserve the left edge on every page; the library default does so only on page zero.
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), gestureInclusion = gestures,
            userScrollEnabled = !zoomed,
            key = { photos[it].name }) { page ->
            photos.getOrNull(page)?.let { photo ->
                if (page == pager.currentPage) ZoomablePhoto(photo, zoom,
                    stringResource(if (controls.actionsVisible) R.string.hide_photo_actions else R.string.show_photo_actions), controls::tap)
                else LocalPhoto(photo, Modifier.fillMaxSize(), ContentScale.Fit)
            }
        }
        AnimatedVisibility(controls.counterVisible, Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Text("${(pager.currentPage + 1).coerceAtMost(photos.size)} / ${photos.size}",
                Modifier.padding(top = 16.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall)
        }
        AnimatedVisibility(controls.actionsVisible, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            TextButton(onClick = { deleteId = photos.getOrNull(pager.currentPage)?.nameWithoutExtension },
                enabled = controls.actionsVisible && deleteId == null && !deleting,
                modifier = Modifier.padding(bottom = 8.dp).width(80.dp).heightIn(min = 48.dp).testTag("delete-photo")) {
                Text("Delete", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    AlertDialog(visible = deleteId != null, onDismissRequest = { if (!deleting) deleteId = null },
        title = { Text("Delete photo?") }, text = { Text(error ?: "Removes only the copy on this watch.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(modifier = Modifier.testTag("confirm-delete"), onClick = {
                if (deleting) return@ConfirmButton
                val id = deleteId ?: return@ConfirmButton
                deleting = true
                scope.launch {
                    runCatching { onDelete(id) }.onSuccess { deleteId = null; error = null }
                        .onFailure {
                            if (it is CancellationException) throw it
                            error = it.message ?: "Could not delete. Try again."
                        }
                    deleting = false
                }
            })
        }, dismissButton = { AlertDialogDefaults.DismissButton(modifier = Modifier.testTag("cancel-delete"), onClick = { if (!deleting) { deleteId = null; error = null } }) })
}

@Composable
internal fun LocalPhoto(file: File, modifier: Modifier, scale: ContentScale,
    detailed: Boolean = false, onImageSize: (Size) -> Unit = {}) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val baseDimension = with(LocalDensity.current) {
        if (scale == ContentScale.Crop) ((configuration.screenWidthDp - 56).coerceAtLeast(2) / 2f).dp.roundToPx()
        else minOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1).dp.roundToPx()
    }.coerceAtLeast(1)
    // Only two decode levels: display size normally and a bounded detail request while zoomed.
    val dimension = if (detailed) (baseDimension * MAX_PHOTO_ZOOM).toInt().coerceAtMost(1024) else baseDimension
    val request = remember(context, file.path, dimension, scale, detailed) {
        ImageRequest.Builder(context).data(file).size(dimension)
            .precision(if (detailed) Precision.EXACT else Precision.INEXACT)
            // Bound both dimensions even for very wide/tall originals; cropping is display-only.
            .scale(Scale.FIT).memoryCacheKey("${file.path}:$dimension").crossfade(false).build()
    }
    var failed by remember(file.path) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(model = request,
            contentDescription = "Saved photo", modifier = Modifier.fillMaxSize(), contentScale = scale,
            onError = { failed = true }, onSuccess = {
                failed = false
                onImageSize(Size(it.result.drawable.intrinsicWidth.toFloat(), it.result.drawable.intrinsicHeight.toFloat()))
            })
        if (failed) Text("Cannot display photo", Modifier.padding(12.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    }
}
