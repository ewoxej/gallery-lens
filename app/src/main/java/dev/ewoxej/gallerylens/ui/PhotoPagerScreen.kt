package dev.ewoxej.gallerylens.ui

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.ewoxej.gallerylens.R
import dev.ewoxej.gallerylens.data.PhotoEntity
import dev.ewoxej.gallerylens.ocr.OcrBlock
import dev.ewoxej.gallerylens.ocr.OcrLayout
import kotlinx.coroutines.launch

@Composable
fun PhotoPagerScreen(vm: MainViewModel, startIndex: Int, onClose: () -> Unit) {
    val photos by vm.results.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    if (photos.isEmpty()) { onClose(); return }

    val context = LocalContext.current
    val density = LocalDensity.current
    val start = startIndex.coerceIn(0, photos.size - 1)
    val pagerState = rememberPagerState(initialPage = start, pageCount = { photos.size })
    var showText by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pathToShow by remember { mutableStateOf<String?>(null) }

    val current = photos.getOrNull(pagerState.currentPage)

    // Swipe-down-to-dismiss: the photo slides down and fades as you drag.
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val dismissPx = with(density) { 180.dp.toPx() }
    val fadePx = with(density) { 520.dp.toPx() }
    val progress = (offsetY.value / fadePx).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black, alpha = 1f - progress * 0.85f) },
    ) {
        // Moving/fading content: the pager + its text panel.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = offsetY.value; alpha = 1f - progress * 0.75f }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dy ->
                            scope.launch { offsetY.snapTo((offsetY.value + dy).coerceAtLeast(0f)) }
                        },
                        onDragEnd = {
                            if (offsetY.value > dismissPx) onClose()
                            else scope.launch { offsetY.animateTo(0f) }
                        },
                        onDragCancel = { scope.launch { offsetY.animateTo(0f) } },
                    )
                },
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                PhotoPage(
                    photo = photos[page],
                    terms = if (showText) queryTerms(query) else emptyList(),
                    showOverlay = showText,
                )
            }

            if (showText) {
                val text = current?.ocrText
                if (!text.isNullOrBlank()) {
                    TextPanel(text, Modifier.align(Alignment.BottomCenter))
                }
            }
        }

        // Back button, top-left (stays put while the photo drags).
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(44.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
        }

        // Actions, top-right: text toggle, share, overflow menu.
        Row(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            CircleAction(
                icon = Icons.AutoMirrored.Filled.Subject,
                description = stringResource(R.string.action_show_text),
                tint = if (showText) MaterialTheme.colorScheme.primary else Color.White,
                onClick = { showText = !showText },
            )
            CircleAction(
                icon = Icons.Default.Share,
                description = stringResource(R.string.action_share),
                onClick = { current?.let { shareImages(context, listOf(it.uri)) } },
            )
            Box {
                CircleAction(
                    icon = Icons.Default.MoreVert,
                    description = stringResource(R.string.action_more),
                    onClick = { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_copy_image)) },
                        onClick = {
                            menuOpen = false
                            current?.let {
                                copyImages(context, listOf(it.uri))
                                Toast.makeText(context, context.getString(R.string.image_copied), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_show_path)) },
                        onClick = { menuOpen = false; current?.let { pathToShow = filePath(context, it.uri) } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_ignore_photo)) },
                        onClick = { menuOpen = false; current?.let { vm.setIgnored(it.id, true); onClose() } },
                    )
                }
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
private fun CircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(start = 8.dp)
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

@Composable
private fun PhotoPage(photo: PhotoEntity, terms: List<String>, showOverlay: Boolean) {
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val lineCopied = stringResource(R.string.line_copied)
    val blocks = remember(photo.blocksJson) { OcrLayout.fromJson(photo.blocksJson) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val ow = photo.ocrWidth
        val oh = photo.ocrHeight
        val hasGeom = ow != null && oh != null && ow > 0 && oh > 0

        val (fw, fh) = if (hasGeom) fitInside(cw, ch, ow!!.toFloat() / oh!!.toFloat()) else cw to ch
        val fwDp = with(density) { fw.toDp() }
        val fhDp = with(density) { fh.toDp() }

        Box(
            Modifier
                .align(Alignment.Center)
                .size(fwDp, fhDp)
                .pointerInput(showOverlay, blocks, ow, oh) {
                    if (!showOverlay || !hasGeom || blocks.isEmpty()) return@pointerInput
                    detectTapGestures(onTap = { offset: Offset ->
                        val ox = offset.x / size.width.toFloat() * ow!!
                        val oy = offset.y / size.height.toFloat() * oh!!
                        val hit = blocks.firstOrNull { b ->
                            ox >= b.left && ox <= b.right && oy >= b.top && oy <= b.bottom
                        }
                        if (hit != null && hit.text.isNotBlank()) {
                            clipboard.setText(AnnotatedString(hit.text))
                            Toast.makeText(context, lineCopied, Toast.LENGTH_SHORT).show()
                        }
                    })
                },
        ) {
            AsyncImage(
                model = photo.uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (showOverlay && hasGeom && blocks.isNotEmpty()) {
                Canvas(Modifier.fillMaxSize()) {
                    val sx = size.width / ow!!.toFloat()
                    val sy = size.height / oh!!.toFloat()
                    for (b in blocks) {
                        val matched = terms.isNotEmpty() && blockMatches(b.text, terms)
                        val tl = Offset(b.left * sx, b.top * sy)
                        val bs = androidx.compose.ui.geometry.Size((b.right - b.left) * sx, (b.bottom - b.top) * sy)
                        if (matched) {
                            drawRect(accent.copy(alpha = 0.25f), tl, bs)
                            drawRect(accent, tl, bs, style = Stroke(width = 5f))
                        } else {
                            drawRect(accent.copy(alpha = 0.85f), tl, bs, style = Stroke(width = 3f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextPanel(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val textCopied = stringResource(R.string.text_copied)
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(16.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.text_on_photo),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, textCopied, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.CenterEnd).size(36.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy_all), tint = Color.White)
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier
                .padding(top = 8.dp)
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

private fun fitInside(cw: Float, ch: Float, aspect: Float): Pair<Float, Float> {
    if (cw <= 0f || ch <= 0f || !aspect.isFinite() || aspect <= 0f) return cw to ch
    return if (aspect > cw / ch) cw to (cw / aspect) else (ch * aspect) to ch
}

private fun queryTerms(raw: String): List<String> =
    raw.lowercase().replace('ё', 'е')
        .split(Regex("\\s+"))
        .map { it.filter(Char::isLetterOrDigit) }
        .filter { it.isNotEmpty() }

private fun blockMatches(blockText: String, terms: List<String>): Boolean {
    val words = blockText.lowercase().replace('ё', 'е')
        .split(Regex("\\s+"))
        .map { it.filter(Char::isLetterOrDigit) }
        .filter { it.isNotEmpty() }
    return terms.all { term -> words.any { it.startsWith(term) } }
}
