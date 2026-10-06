package be.cameratv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.cameratv.controller.AppController
import be.cameratv.model.Screen

/** Vue racine : observe l'état du modèle (via le contrôleur) et affiche l'écran courant. */
@Composable
fun AppView(controller: AppController) {
    val state by controller.state.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(CameraTvColors.Background)
    ) {
        when (val screen = state.screen) {
            Screen.Setup -> SetupView(state, controller)
            Screen.Loading -> LoadingView()
            Screen.Grid -> GridView(state, controller)
            is Screen.Fullscreen -> FullscreenView(state, screen.channel, controller)
        }
    }
}
