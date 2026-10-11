package dev.ewoxej.gallerylens.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.ewoxej.gallerylens.R
import dev.ewoxej.gallerylens.data.AlbumSummary
import dev.ewoxej.gallerylens.data.PhotoEntity
import dev.ewoxej.gallerylens.ui.theme.MonoFont
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(vm: MainViewModel, onOpen: (Int) -> Unit) {
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

    val inAlbum = viewMode == ViewMode.ALBUMS && openAlbum != null
    val inAlbumList = viewMode == ViewMode.ALBUMS && openAlbum == null

    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selected.isNotEmpty()
    var pathToShow by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = selectionMode) { selected = emptySet() }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun selectedUris() = results.filter { it.id in selected }.map { it.uri }

    val title = when {
        inAlbum -> if (openAlbum!!.isEmpty()) unknownAlbum else openAlbum!!
        viewMode == ViewMode.ALBUMS -> stringResource(R.string.scope_albums)
        else -> stringResource(R.string.scope_all_photos)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        if (selectionMode) {
            SelectionBar(
                count = selected.size,
                onClose = { selected = emptySet() },
                onShare = { shareImages(context, selectedUris()) },
                onCopy = if (selected.size == 1) {
                    {
                        copyImages(context, selectedUris())
                        Toast.makeText(context, context.getString(R.string.image_copied), Toast.LENGTH_SHORT).show()
                    }
                } else null,
                onPath = if (selected.size == 1) {
                    { pathToShow = filePath(context, selectedUris().first()) }
                } else null,
                onIgnore = { vm.setIgnored(selected, true); selected = emptySet() },
            )
        } else {
            // Fixed-height title row so the title sits in the same spot on every
            // tab, regardless of the back arrow / "With text" pill.
            Row(
                Modifier.fillMaxWidth().height(HEADER_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inAlbum) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(22.dp)
                            .clickable(onClick = vm::showAlbums),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!inAlbumList) {
                    WithTextPill(active = onlyWithText, onClick = { vm.setOnlyWithText(!onlyWithText) })
                }
            }

            if (!inAlbumList) {
                SearchField(query, vm::onQueryChange)
                Spacer(Modifier.size(10.dp))
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val sections = remember(results) { buildSections(results) }
            val gridItems = sections.first
            val labels = sections.second
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Indexing info scrolls together with the photos (not pinned).
                    if (status.isIndexing) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            IndexingCard(status.done, status.total)
                        }
                    }
                    items(
                        items = gridItems,
                        key = { it.key },
                        span = { item ->
                            if (item is GridItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                        },
                    ) { item ->
                        when (item) {
                            is GridItem.Header -> MonthHeader(item.label, item.count)
                            is GridItem.Cell -> PhotoCell(
                                photo = item.photo,
                                selected = item.photo.id in selected,
                                onClick = { if (selectionMode) toggle(item.photo.id) else onOpen(item.photoIndex) },
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

@Composable
private fun WithTextPill(active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.filter_with_text), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

@Composable
private fun IndexingCard(done: Int, total: Int) {
    val progress = if (total > 0) done.toFloat() / total else 0f
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.TextFields, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Text(
                    stringResource(R.string.recognising_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text("$done / $total", fontFamily = MonoFont, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun AlbumGrid(albums: List<AlbumSummary>, unknownName: String, onOpen: (String) -> Unit) {
    if (albums.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.gallery_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 2.dp),
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
                .clip(MaterialTheme.shapes.large),
            contentScale = ContentScale.Crop,
        )
        Text(
            album.bucketName ?: unknownName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        Text(
            "${album.count}",
            fontFamily = MonoFont,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onCopy: (() -> Unit)?,
    onPath: (() -> Unit)?,
    onIgnore: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_back))
        }
        Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy_image))
            }
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

private sealed interface GridItem {
    val key: Any

    data class Header(val label: String, val monthKey: String, val count: Int) : GridItem {
        override val key get() = "h:$monthKey"
    }

    data class Cell(val photo: PhotoEntity, val photoIndex: Int) : GridItem {
        override val key get() = photo.id
    }
}

/** Flattens date-sorted photos into [header, cells…] with a per-item section label. */
private fun buildSections(photos: List<PhotoEntity>): Pair<List<GridItem>, List<String>> {
    val items = ArrayList<GridItem>(photos.size + 8)
    val labels = ArrayList<String>(photos.size + 8)
    // Pre-count photos per month for the header count.
    val counts = LinkedHashMap<String, Int>()
    photos.forEach { counts[monthKey(it.dateTakenMs)] = (counts[monthKey(it.dateTakenMs)] ?: 0) + 1 }
    var lastKey: String? = null
    var curLabel = ""
    photos.forEachIndexed { i, p ->
        val key = monthKey(p.dateTakenMs)
        if (key != lastKey) {
            curLabel = monthYear(p.dateTakenMs)
            items += GridItem.Header(curLabel, key, counts[key] ?: 0)
            labels += curLabel
            lastKey = key
        }
        items += GridItem.Cell(p, i)
        labels += curLabel
    }
    return items to labels
}

@Composable
private fun MonthHeader(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$count", fontFamily = MonoFont, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(photo: PhotoEntity, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.ocrText?.take(60),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // Marker badge for photos that carry recognised text.
        if (photo.ocrText != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC0A0F0D)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.TextFields, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
            }
        }
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
private val HEADER_HEIGHT = 52.dp

private fun monthYear(ms: Long): String {
    val locale = Locale.getDefault()
    val s = SimpleDateFormat("LLLL yyyy", locale).format(Date(ms))
    return s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

private fun monthKey(ms: Long): String =
    SimpleDateFormat("yyyy-MM", Locale.US).format(Date(ms))
