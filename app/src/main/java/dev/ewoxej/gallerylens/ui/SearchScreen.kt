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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalDensity
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
    val onlyWithText by vm.onlyWithText.collectAsStateWithLifecycle()
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

        // Gallery-mode filter (no effect while searching — results are already text).
        if (query.isBlank()) {
            FilterChip(
                selected = onlyWithText,
                onClick = { vm.setOnlyWithText(!onlyWithText) },
                label = { Text(stringResource(R.string.filter_with_text)) },
                leadingIcon = if (onlyWithText) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else null,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

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
            // Group photos into month sections (Google-Photos style): a full-width
            // header before each month, then that month's thumbnails.
            val sections = remember(results) { buildSections(results) }
            val gridItems = sections.first
            val labels = sections.second
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = gridItems,
                        key = { it.key },
                        span = { item ->
                            if (item is GridItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                        },
                    ) { item ->
                        when (item) {
                            is GridItem.Header -> MonthHeader(item.label)
                            is GridItem.Cell -> PhotoCell(item.photo) { onOpen(item.photoIndex) }
                        }
                    }
                }
                DateScrubber(
                    gridState = gridState,
                    itemCount = gridItems.size,
                    labelAt = { idx -> labels.getOrElse(idx) { "" } },
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

private sealed interface GridItem {
    val key: Any

    data class Header(val label: String, val monthKey: String) : GridItem {
        override val key get() = "h:$monthKey"
    }

    data class Cell(val photo: PhotoEntity, val photoIndex: Int) : GridItem {
        override val key get() = photo.id
    }
}

/** Flattens the date-sorted photos into [header, cells…] and a parallel list of
 *  the section label for each item index (used by the scrubber bubble). */
private fun buildSections(photos: List<PhotoEntity>): Pair<List<GridItem>, List<String>> {
    val items = ArrayList<GridItem>(photos.size + 8)
    val labels = ArrayList<String>(photos.size + 8)
    var lastKey: String? = null
    var curLabel = ""
    photos.forEachIndexed { i, p ->
        val key = monthKey(p.dateTakenMs)
        if (key != lastKey) {
            curLabel = monthYear(p.dateTakenMs)
            items += GridItem.Header(curLabel, key)
            labels += curLabel
            lastKey = key
        }
        items += GridItem.Cell(p, i)
        labels += curLabel
    }
    return items to labels
}

@Composable
private fun MonthHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, start = 2.dp),
    )
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

/**
 * Google-Photos-style fast-scroll rail: a thumb on the right that tracks scroll
 * position and, when dragged, jumps the grid and shows the month at that spot.
 * Only shown when the list is long enough to be worth scrubbing.
 */
@Composable
private fun DateScrubber(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    itemCount: Int,
    labelAt: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    if (itemCount < MIN_ITEMS_FOR_SCRUBBER) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragLabel by remember { mutableStateOf("") }
    var heightPx by remember { mutableFloatStateOf(0f) }

    val thumbHalfPx = with(density) { 24.dp.toPx() }
    val scrollFrac by remember {
        derivedStateOf {
            if (itemCount <= 1) 0f
            else (gridState.firstVisibleItemIndex.toFloat() / (itemCount - 1)).coerceIn(0f, 1f)
        }
    }

    fun onScrub(y: Float) {
        if (heightPx <= 0f) return
        dragY = y.coerceIn(0f, heightPx)
        val frac = (dragY / heightPx).coerceIn(0f, 1f)
        val idx = (frac * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
        dragLabel = labelAt(idx)
        scope.launch { gridState.scrollToItem(idx) }
    }

    Box(
        modifier
            .width(40.dp)
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(itemCount) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> dragging = true; onScrub(offset.y) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ -> onScrub(change.position.y) },
                )
            },
    ) {
        // Thumb: follows scroll when idle, follows the finger while dragging.
        val centerY = if (dragging) dragY else scrollFrac * heightPx
        val topY = (centerY - thumbHalfPx).coerceIn(0f, (heightPx - 2 * thumbHalfPx).coerceAtLeast(0f))
        Surface(
            color = if (dragging) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 3.dp)
                .offset { IntOffset(0, topY.roundToInt()) }
                .size(width = 6.dp, height = 48.dp),
        ) {}

        if (dragging && dragLabel.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(50),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, (dragY - thumbHalfPx).roundToInt()) }
                    .padding(end = 20.dp),
            ) {
                Text(
                    dragLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private const val MIN_ITEMS_FOR_SCRUBBER = 30

private fun monthYear(ms: Long): String {
    val locale = Locale.getDefault()
    val s = SimpleDateFormat("LLLL yyyy", locale).format(Date(ms))
    return s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/** Locale-independent grouping key, e.g. "2026-08". */
private fun monthKey(ms: Long): String =
    SimpleDateFormat("yyyy-MM", Locale.US).format(Date(ms))
