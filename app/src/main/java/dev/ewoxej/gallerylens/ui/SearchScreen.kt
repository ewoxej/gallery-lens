package dev.ewoxej.gallerylens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.ewoxej.gallerylens.R
import dev.ewoxej.gallerylens.data.PhotoEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SearchScreen(vm: MainViewModel, onOpen: (Int) -> Unit, onOpenSettings: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                }
            },
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            singleLine = true,
        )

        if (status.isIndexing) {
            val progress = if (status.total > 0) status.done.toFloat() / status.total else 0f
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.indexing_progress, status.done, status.total),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) stringResource(R.string.gallery_empty)
                    else stringResource(R.string.no_results),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(results, key = { _, p -> p.id }) { index, photo ->
                        PhotoCell(photo) { onOpen(index) }
                    }
                }
                DateScrubber(
                    photos = results,
                    gridState = gridState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun PhotoCell(photo: PhotoEntity, onOpen: () -> Unit) {
    AsyncImage(
        model = photo.uri,
        contentDescription = photo.ocrText?.take(60),
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onOpen() },
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun DateScrubber(
    photos: List<PhotoEntity>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var bubbleY by remember { mutableFloatStateOf(0f) }
    var label by remember { mutableStateOf("") }
    var heightPx by remember { mutableFloatStateOf(0f) }

    fun onScrub(y: Float) {
        if (photos.isEmpty() || heightPx <= 0f) return
        val frac = (y / heightPx).coerceIn(0f, 1f)
        val idx = (frac * (photos.size - 1)).roundToInt().coerceIn(0, photos.size - 1)
        bubbleY = y
        label = monthYear(photos[idx].dateTakenMs)
        scope.launch { gridState.scrollToItem(idx) }
    }

    Box(
        modifier
            .width(28.dp)
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(photos.size) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> dragging = true; onScrub(offset.y) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ -> onScrub(change.position.y) },
                )
            },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = 40.dp)
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (dragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                ),
        )
        if (dragging && label.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(50),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(x = 0, y = (bubbleY - 40f).roundToInt()) }
                    .padding(end = 20.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun monthYear(ms: Long): String {
    val locale = Locale.getDefault()
    val s = SimpleDateFormat("LLLL yyyy", locale).format(Date(ms))
    return s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
