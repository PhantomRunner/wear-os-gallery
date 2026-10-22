package com.pixelwatchgallery.wear

import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
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
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = PhotoRepository.get(applicationContext)
        setContent { MaterialTheme { WatchGallery(repository, lifecycle) } }
    }
}

@Composable
private fun WatchGallery(repository: PhotoRepository, lifecycle: Lifecycle) {
    // Navigation retains destination content. Capture the State holder, then read its value
    // inside each destination so that receipts/deletions invalidate the visible screen.
    val galleryState = repository.state.collectAsStateWithLifecycle()
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
    val nav = rememberSwipeDismissableNavController()
    var selectedId by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SwipeDismissableNavHost(navController = nav, startDestination = "gallery") {
            composable("gallery") {
                val gallery by galleryState
                GalleryGrid(gallery.photos, error) { file -> selectedId = file.nameWithoutExtension; nav.navigate("viewer") }
            }
            composable("viewer") {
                val gallery by galleryState
                // A restored back stack must wait for disk loading before deciding it is empty.
                if (gallery.loaded) PhotoViewer(gallery.photos, selectedId, onEmpty = { nav.popBackStack() }, onDelete = { repository.delete(it) })
            }
        }
    }
}

@Composable
private fun GalleryGrid(photos: List<File>, error: String?, onOpen: (File) -> Unit) {
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
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    LazyVerticalGrid(columns = GridCells.Fixed(2), state = state,
        modifier = Modifier.fillMaxSize().testTag("gallery-grid").requestFocusOnHierarchyActive().onRotaryScrollEvent {
            scope.launch { state.scrollBy(it.verticalScrollPixels) }; true
        }.focusable(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(2) }, contentType = "header") {
            Text("${photos.size} ${if (photos.size == 1) "photo" else "photos"}", Modifier.fillMaxWidth().padding(bottom = 4.dp),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
        }
        items(photos, key = { it.name }, contentType = { "photo" }) { photo ->
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).clickable { onOpen(photo) }) {
                LocalPhoto(photo, Modifier.fillMaxSize(), ContentScale.Crop)
            }
        }
    }
}

@OptIn(ExperimentalWearFoundationApi::class)
@Composable
private fun PhotoViewer(photos: List<File>, initialId: String, onEmpty: () -> Unit, onDelete: suspend (String) -> Unit) {
    val initialPage = remember { photos.indexOfFirst { it.nameWithoutExtension == initialId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { photos.size })
    val zoom = remember(photos.getOrNull(pager.currentPage)?.name) { PhotoZoomState() }
    val zoomed by remember(zoom) { derivedStateOf { zoom.isZoomed } }
    val crownThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    val crown = remember(crownThreshold) { CrownPhotoStepper(crownThreshold) }
    var crownTarget by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
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
    LaunchedEffect(crownTarget) {
        val target = crownTarget ?: return@LaunchedEffect
        if (photos.isNotEmpty()) pager.scrollToPage(target.coerceIn(0, photos.lastIndex))
        if (crownTarget == target) crownTarget = null
    }
    LaunchedEffect(photos.size) {
        if (photos.isEmpty()) onEmpty()
        else if (pager.currentPage >= photos.size) pager.scrollToPage(photos.lastIndex)
    }
    if (photos.isEmpty()) return
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("photo-viewer")
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
                if (page == pager.currentPage) ZoomablePhoto(photo, zoom)
                else LocalPhoto(photo, Modifier.fillMaxSize(), ContentScale.Fit)
            }
        }
        Text("${(pager.currentPage + 1).coerceAtMost(photos.size)} / ${photos.size}",
            Modifier.align(Alignment.TopCenter).padding(top = 16.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { deleteId = photos.getOrNull(pager.currentPage)?.nameWithoutExtension },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).width(80.dp).heightIn(min = 48.dp)) {
            Text("Delete", style = MaterialTheme.typography.labelSmall)
        }
    }
    AlertDialog(visible = deleteId != null, onDismissRequest = { if (!deleting) deleteId = null },
        title = { Text("Delete photo?") }, text = { Text(error ?: "Removes only the copy on this watch.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
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
        }, dismissButton = { AlertDialogDefaults.DismissButton(onClick = { if (!deleting) { deleteId = null; error = null } }) })
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
