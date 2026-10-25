package com.pixelwatchgallery.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val repository = TransferRepository.get(applicationContext)
        setContent { GalleryTheme { GalleryRoute(repository) } }
    }
}

@Composable
private fun GalleryRoute(repository: TransferRepository) {
    val photos by repository.photos.collectAsStateWithLifecycle()
    val batch by repository.batch.collectAsStateWithLifecycle()
    val busy by repository.busy.collectAsStateWithLifecycle()
    val notice by repository.notice.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { repository.select(it) }
    val state = remember(photos, batch, busy, notice) { PhoneGalleryState(photos, batch?.progress, busy, notice, batch?.id) }
    GalleryScreen(state,
        onAdd = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSend = repository::sendPending, onRetry = repository::retry, onRemove = repository::remove,
        onNoticeDismiss = repository::dismissNotice)
}
