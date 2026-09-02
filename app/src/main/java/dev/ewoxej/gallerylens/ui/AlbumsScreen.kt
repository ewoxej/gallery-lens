package dev.ewoxej.gallerylens.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ewoxej.gallerylens.R
import dev.ewoxej.gallerylens.work.Album

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(vm: MainViewModel, onBack: () -> Unit) {
    // null while loading; a (possibly empty) list once MediaStore is read.
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    // Set of selected album keys. Starts from the saved selection (null = all).
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val list = vm.loadAlbums()
        val saved = vm.currentAlbumSelection()
        selected = saved ?: list.map { it.key }.toSet() // null selection = all on
        albums = list
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.albums_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    val list = albums
                    TextButton(
                        onClick = {
                            if (list != null) {
                                // All selected -> null (also auto-includes future albums).
                                val all = list.map { it.key }.toSet()
                                vm.applyAlbumSelection(if (selected == all) null else selected)
                            }
                            onBack()
                        },
                        enabled = list != null,
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        val list = albums
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.albums_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // Select-all / none toggle.
            val allKeys = remember(list) { list.map { it.key }.toSet() }
            val allOn = selected.containsAll(allKeys) && allKeys.isNotEmpty()
            AlbumRow(
                title = stringResource(R.string.albums_select_all),
                subtitle = null,
                checked = allOn,
                onToggle = { selected = if (allOn) emptySet() else allKeys },
            )
            HorizontalDivider()

            LazyColumn(Modifier.fillMaxWidth()) {
                items(list, key = { it.key }) { album ->
                    AlbumRow(
                        title = album.name,
                        subtitle = stringResource(R.string.albums_count, album.count),
                        checked = album.key in selected,
                        onToggle = {
                            selected = if (album.key in selected) selected - album.key
                            else selected + album.key
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(title: String, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
