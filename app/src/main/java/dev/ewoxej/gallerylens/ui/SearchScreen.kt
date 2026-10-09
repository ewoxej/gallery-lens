package dev.ewoxej.gallerylens.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.ewoxej.gallerylens.R
import dev.ewoxej.gallerylens.data.AlbumSummary
import dev.ewoxej.gallerylens.data.PhotoEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(vm: MainViewModel, onOpen: (Int) -> Unit, onOpenSettings: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val onlyWithText by vm.onlyWithText.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val openAlbum by vm.openAlbum.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val unknownAlbum = stringResource(R.string.album_unknown)
    val context = LocalContext.current

    val inAlbumList = viewMode == ViewMode.ALBUMS && openAlbum == null

    // Multi-select in the photo grid (long-press to start, tap to add/remove).
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selected.isNotEmpty()
    var pathToShow by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = selectionMode) { selected = emptySet() }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun selectedUris() = results.filter { it.id in selected }.map { it.uri }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (selectionMode) {
            SelectionBar(
                count = selected.size,
                onClose = { selected = emptySet() },
                onShare = { shareImages(context, selectedUris()) },
                onCopy = {
                    copyImages(context, selectedUris())
                    Toast.makeText(context, context.getString(R.string.image_copied), Toast.LENGTH_SHORT).show()
                },
                onPath = if (selected.size == 1) {
                    { pathToShow = filePath(context, selectedUris().first()) }
                } else null,
                onIgnore = { vm.setIgnored(selected, true); selected = emptySet() },
            )
        } else {
            ScopeBar(
                viewMode = viewMode,
                openAlbum = openAlbum,
                albumTitle = openAlbum?.let { if (it.isEmpty()) unknownAlbum else it }.orEmpty(),
                onlyWithText = onlyWithText,
                showFilter = !inAlbumList && query.isBlank(),
                onAllPhotos = vm::showAllPhotos,
                onAlbums = vm::showAlbums,
                onBackToAlbums = vm::showAlbums,
                onToggleFilter = { vm.setOnlyWithText(!onlyWithText) },
            )

            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
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
        }

        if (inAlbumList) {
            AlbumGrid(albums, unknownAlbum, onOpen = { vm.openAlbum(it) })
        } else if (results.isEmpty()) {
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
                            is GridItem.Cell -> PhotoCell(
                                photo = item.photo,
                                selected = item.photo.id in selected,
                                onClick = {
                                    if (selectionMode) toggle(item.photo.id) else onOpen(item.photoIndex)
                                },
                                onLongClick = { toggle(item.photo.id) },
                            )
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

    pathToShow?.let { path ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { pathToShow = null },
            title = { Text(stringResource(R.string.action_show_path)) },
            text = { Text(path, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(path))
                    Toast.makeText(context, context.getString(R.string.path_copied), Toast.LENGTH_SHORT).show()
                    pathToShow = null
                }) { Text(stringResource(R.string.action_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { pathToShow = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

/** Selection action bar shown in place of the scope row while photos are selected. */
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onPath: (() -> Unit)?,
    onIgnore: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_back))
        }
        Text("$count", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy_image))
        }
        if (onPath != null) {
            IconButton(onClick = onPath) {
                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.action_show_path))
            }
        }
        IconButton(onClick = onIgnore) {
            Icon(Icons.Default.VisibilityOff, contentDescription = stringResource(R.string.action_ignore_photo))
        }
    }
}

/** The scope row above the search field: "All Photos ▾" / "Albums ▾" / album name,
 *  with the "with text only" filter chip on the right. */
@Composable
private fun ScopeBar(
    viewMode: ViewMode,
    openAlbum: String?,
    albumTitle: String,
    onlyWithText: Boolean,
    showFilter: Boolean,
    onAllPhotos: () -> Unit,
    onAlbums: () -> Unit,
    onBackToAlbums: () -> Unit,
    onToggleFilter: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (viewMode == ViewMode.ALBUMS && openAlbum != null) {
            // Inside an album → breadcrumb back to the album list.
            Row(
                Modifier.clickable(onClick = onBackToAlbums).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                Text(albumTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp))
            }
        } else {
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.clickable(onClick = { menu = true }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (viewMode == ViewMode.ALL) R.string.scope_all_photos else R.string.scope_albums),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scope_all_photos)) },
                        onClick = { menu = false; onAllPhotos() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scope_albums)) },
                        onClick = { menu = false; onAlbums() },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        if (showFilter) {
            FilterChip(
                selected = onlyWithText,
                onClick = onToggleFilter,
                label = { Text(stringResource(R.string.filter_with_text)) },
                leadingIcon = if (onlyWithText) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else null,
            )
        }
    }
}

@Composable
private fun AlbumGrid(albums: List<AlbumSummary>, unknownName: String, onOpen: (String) -> Unit) {
    if (albums.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.gallery_empty), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(albums, key = { it.bucketName ?: "" }) { album ->
            AlbumCell(album, unknownName) { onOpen(album.bucketName ?: "") }
        }
    }
}

@Composable
private fun AlbumCell(album: AlbumSummary, unknownName: String, onOpen: () -> Unit) {
    Column(Modifier.clickable(onClick = onOpen)) {
        AsyncImage(
            model = album.uri,
            contentDescription = album.bucketName,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(
            album.bucketName ?: unknownName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
        Text(
            "${album.count}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp),
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(photo: PhotoEntity, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.ocrText?.take(60),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
            )
        }
    }
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
