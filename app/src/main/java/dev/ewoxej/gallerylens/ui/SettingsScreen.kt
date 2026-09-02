package dev.ewoxej.gallerylens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ewoxej.gallerylens.R
import dev.ewoxej.gallerylens.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onOpenAlbums: () -> Unit) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    var confirmReindex by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow(stringResource(R.string.stat_total), stats.total)
                    StatRow(stringResource(R.string.stat_with_text), stats.done)
                    StatRow(stringResource(R.string.stat_no_text), stats.noText)
                    StatRow(stringResource(R.string.stat_queued), stats.pending)
                    StatRow(stringResource(R.string.stat_errors), stats.failed)
                }
            }

            Text(stringResource(R.string.indexing_title), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = onOpenAlbums,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.albums_action))
            }
            Text(
                stringResource(R.string.albums_action_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { confirmReindex = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = stats.total > 0,
            ) {
                Text(stringResource(R.string.reindex_action))
            }
            Text(
                stringResource(R.string.reindex_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CloudSection(vm)
        }
    }

    if (confirmReindex) {
        AlertDialog(
            onDismissRequest = { confirmReindex = false },
            title = { Text(stringResource(R.string.reindex_confirm_title)) },
            text = { Text(stringResource(R.string.reindex_confirm_message, stats.total)) },
            confirmButton = {
                TextButton(onClick = { vm.reindexAll(); confirmReindex = false }) {
                    Text(stringResource(R.string.reindex_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReindex = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CloudSection(vm: MainViewModel) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Settings.cloudEnabled(context)) }
    var always by remember { mutableStateOf(Settings.cloudAlways(context)) }
    var apiKey by remember { mutableStateOf(Settings.apiKey(context)) }
    var showKey by remember { mutableStateOf(false) }

    Text(stringResource(R.string.cloud_title), style = MaterialTheme.typography.titleMedium)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.cloud_enable),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 12.dp).weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = { enabled = it; Settings.setCloudEnabled(context, it) },
        )
    }

    // "Send every photo to Claude" — only meaningful while cloud OCR is on.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 12.dp).weight(1f)) {
            Text(stringResource(R.string.cloud_all), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.cloud_all_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = always && enabled,
            enabled = enabled,
            onCheckedChange = { always = it; Settings.setCloudAlways(context, it) },
        )
    }

    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it; Settings.setApiKey(context, it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.cloud_key_label)) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (showKey) R.string.action_hide_key else R.string.action_show_key,
                    ),
                )
            }
        },
    )

    Text(
        stringResource(R.string.cloud_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Manually push the already-indexed library through the cloud (new photos are
    // queued automatically; this catches everything indexed before cloud was on).
    OutlinedButton(
        onClick = { vm.sendToCloud() },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled && apiKey.isNotBlank(),
    ) {
        Text(stringResource(R.string.cloud_send_now))
    }
    Text(
        stringResource(R.string.cloud_send_now_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("$value", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
