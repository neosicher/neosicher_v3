package com.neosicher.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        // Modo inmersivo: la app tiene prioridad total sobre las barras del
        // sistema (estado y navegación). Se ocultan y solo reaparecen con un
        // gesto deslizante temporal del usuario (BEHAVIOR_SHOW_TRANSIENT_BARS).
        enableImmersiveMode()
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-aplicar el modo inmersivo al recuperar el foco (el sistema puede
        // restaurar las barras tras un diálogo de permiso, notificación, etc.).
        if (hasFocus) enableImmersiveMode()
    }

    private fun enableImmersiveMode() {
        // El contenido ocupa toda la pantalla, por debajo de donde estaban las
        // barras del sistema.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
