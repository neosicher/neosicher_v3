package com.neosicher.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.neosicher.app.ui.NeosicherScreen
import com.neosicher.app.ui.NeosicherViewModel
import com.neosicher.app.ui.theme.NeoColors
import com.neosicher.app.ui.theme.NeosicherTheme

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeosicherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = NeoColors.Background,
                ) {
                    val viewModel: NeosicherViewModel = viewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()

                    // Permiso de cámara (independiente del permiso USB).
                    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

                    // Sincronizar el estado del permiso con el ViewModel.
                    LaunchedEffect(cameraPermission.status) {
                        when {
                            cameraPermission.status.isGranted ->
                                viewModel.onCameraPermissionResult(true)
                            cameraPermission.status.shouldShowRationale ->
                                viewModel.onCameraPermissionResult(false)
                            else ->
                                viewModel.onCameraPermissionResult(false)
                        }
                    }

                    NeosicherScreen(
                        state = state,
                        cameraManager = viewModel.cameraManager,
                        onModeSelected = viewModel::selectMode,
                        onToggleMode = viewModel::toggleMode,
                        onRequestCameraPermission = {
                            cameraPermission.launchPermissionRequest()
                        },
                        onToggleDiagnostics = viewModel::toggleDiagnostics,
                        onFullscreen = { /* Reservado: pantalla completa en próxima iteración. */ },
                    )
                }
            }
        }
    }
}
