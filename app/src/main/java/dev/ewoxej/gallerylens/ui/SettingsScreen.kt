package dev.ewoxej.gallerylens.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ewoxej.gallerylens.R
import dev.ewoxej.gallerylens.data.Settings
import dev.ewoxej.gallerylens.ui.theme.MonoFont

@Composable
fun SettingsScreen(vm: MainViewModel, onOpenAlbums: () -> Unit) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    var confirmReindex by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        StatisticsCard(stats)

        SectionLabel(stringResource(R.string.indexing_title))
        Card {
            SettingsRow(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.albums_action),
                subtitle = stringResource(R.string.albums_action_desc),
                onClick = onOpenAlbums,
            )
            Divider60()
            SettingsRow(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.reindex_action),
                subtitle = stringResource(R.string.reindex_desc),
                onClick = { confirmReindex = true },
            )
        }

        SectionLabel(stringResource(R.string.ocr_title))
        OcrCard()
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
private fun StatisticsCard(stats: Stats) {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconPlate(Icons.Default.PieChart)
                Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${stats.total}", fontFamily = MonoFont, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.photos_in_gallery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            LinearProgressIndicator(
                progress = { if (stats.total > 0) stats.done.toFloat() / stats.total else 0f },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.stat_with_text), stats.done, dot = MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatTile(stringResource(R.string.stat_no_text), stats.noText, dot = null, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.stat_queued), stats.pending, dot = null, Modifier.weight(1f))
                StatTile(stringResource(R.string.stat_errors), stats.failed, dot = null, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, dot: androidx.compose.ui.graphics.Color?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dot != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            }
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$value", fontFamily = MonoFont, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OcrCard() {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(Settings.apiKey(context)) }
    var showKey by remember { mutableStateOf(false) }
    val usedToday = remember { Settings.ocrUsedToday(context) }
    val usedMonth = remember { Settings.ocrUsedThisMonth(context) }

    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(stringResource(R.string.ocr_key_label), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; Settings.setApiKey(context, it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                    leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(if (showKey) R.string.action_hide_key else R.string.action_show_key),
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }
            UsageBar(stringResource(R.string.ocr_usage_label), usedToday, Settings.DAILY_LIMIT)
            UsageBar(stringResource(R.string.ocr_usage_month_label), usedMonth, Settings.MONTHLY_LIMIT)
            Text(
                stringResource(R.string.ocr_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageBar(label: String, value: Int, max: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                Text("$value", fontFamily = MonoFont, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text(" / $max", fontFamily = MonoFont, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LinearProgressIndicator(
            progress = { if (max > 0) (value.toFloat() / max).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

// --- building blocks ---

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { Column { content() } }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontFamily = MonoFont,
        fontSize = 11.sp,
        letterSpacing = 1.3.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
    )
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconPlate(icon, size = 30.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).size(18.dp),
        )
    }
}

@Composable
private fun IconPlate(icon: ImageVector, size: androidx.compose.ui.unit.Dp = 26.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.58f))
    }
}

@Composable
private fun Divider60() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 60.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}
