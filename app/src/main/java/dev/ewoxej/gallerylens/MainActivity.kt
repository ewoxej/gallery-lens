package dev.ewoxej.gallerylens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.ewoxej.gallerylens.ui.AlbumsScreen
import dev.ewoxej.gallerylens.ui.MainViewModel
import dev.ewoxej.gallerylens.ui.PhotoPagerScreen
import dev.ewoxej.gallerylens.ui.SearchScreen
import dev.ewoxej.gallerylens.ui.SettingsScreen
import dev.ewoxej.gallerylens.ui.theme.GalleryLensTheme

class MainActivity : ComponentActivity() {

    private val mediaPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GalleryLensTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: MainViewModel = viewModel()
                    var granted by remember { mutableStateOf(hasPermission()) }

                    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { result ->
                        granted = result || hasPermission()
                        if (granted) vm.startIndexing()
                    }

                    if (granted) {
                        // Kick indexing on every cold start so new photos get picked up.
                        LaunchedEffect(Unit) { vm.startIndexing() }
                        AppNav(vm)
                    } else {
                        PermissionGate(onRequest = { launcher.launch(mediaPermission) })
                    }
                }
            }
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, mediaPermission) == PackageManager.PERMISSION_GRANTED
}

private enum class Tab { PHOTOS, ALBUMS, SETTINGS }

@Composable
private fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScaffold(
                vm,
                onOpen = { index -> nav.navigate("pager/$index") },
                onOpenAlbumPicker = { nav.navigate("albumpicker") },
            )
        }
        composable("pager/{index}") { entry ->
            val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
            PhotoPagerScreen(vm, index, onClose = { nav.popBackStack() })
        }
        composable("albumpicker") {
            AlbumsScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun MainScaffold(vm: MainViewModel, onOpen: (Int) -> Unit, onOpenAlbumPicker: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.PHOTOS) }
    // The Photos/Albums tabs drive the gallery view mode.
    LaunchedEffect(tab) {
        when (tab) {
            Tab.PHOTOS -> vm.showAllPhotos()
            Tab.ALBUMS -> vm.showAlbums()
            Tab.SETTINGS -> {}
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                NavItem(Tab.PHOTOS, tab, Icons.Default.PhotoLibrary, R.string.nav_photos) { tab = it }
                NavItem(Tab.ALBUMS, tab, Icons.Default.Folder, R.string.scope_albums) { tab = it }
                NavItem(Tab.SETTINGS, tab, Icons.Default.Settings, R.string.settings_title) { tab = it }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.SETTINGS -> SettingsScreen(vm, onOpenAlbums = onOpenAlbumPicker)
                else -> SearchScreen(vm, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    tab: Tab,
    selected: Tab,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onSelect: (Tab) -> Unit,
) {
    NavigationBarItem(
        selected = selected == tab,
        onClick = { onSelect(tab) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(labelRes)) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.permission_rationale),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.permission_grant))
        }
    }
}
