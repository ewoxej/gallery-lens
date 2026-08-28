package dev.ewoxej.gallerylens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
                        androidx.compose.runtime.LaunchedEffect(Unit) { vm.startIndexing() }
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

@Composable
private fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "search") {
        composable("search") {
            SearchScreen(
                vm,
                onOpen = { index -> nav.navigate("pager/$index") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("pager/{index}") { entry ->
            val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
            PhotoPagerScreen(vm, index, onClose = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(vm, onBack = { nav.popBackStack() })
        }
    }
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
