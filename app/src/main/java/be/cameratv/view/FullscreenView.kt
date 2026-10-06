package be.cameratv.view

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.cameratv.controller.AppController
import be.cameratv.model.AppState
import be.cameratv.model.Camera
import be.cameratv.model.StreamQuality
import kotlinx.coroutines.delay

private const val OVERLAY_DURATION_MS = 3_000L
private val OverlayBackground = Color.Black.copy(alpha = 0.6f)

/**
 * Une caméra en plein écran (flux principal, son actif).
 *
 * - Mode normal : bandeau d'information temporaire (nom + raccourcis), qui s'efface.
 * - Mode PTZ : bandeaux permanents en haut et en bas de l'écran, la vidéo reste dégagée au centre.
 */
@Composable
fun FullscreenView(state: AppState, channel: Int, controller: AppController) {
    val camera = state.cameras.firstOrNull { it.channel == channel }
    val url = camera?.let { controller.streamUrl(it, StreamQuality.MAIN) }
    val ptzMode = state.ptzMode

    // Le bandeau réapparaît à chaque changement de caméra et à la sortie du mode PTZ, puis s'efface.
    var overlayVisible by remember { mutableStateOf(true) }
    LaunchedEffect(channel, ptzMode) {
        if (ptzMode) {
            overlayVisible = false
        } else {
            overlayVisible = true
            delay(OVERLAY_DURATION_MS)
            overlayVisible = false
        }
    }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (overlayVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "overlayAlpha",
    )

    Box(Modifier.fillMaxSize()) {
        RtspPlayer(url = url, muted = false, modifier = Modifier.fillMaxSize())

        if (ptzMode) {
            PtzOverlay(channel = channel, camera = camera)
        } else {
            InfoOverlay(
                channel = channel,
                camera = camera,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(32.dp)
                    .alpha(overlayAlpha),
            )
        }
    }
}

/** Bandeau temporaire du mode normal. */
@Composable
private fun InfoOverlay(channel: Int, camera: Camera?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(OverlayBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CameraTitle(channel = channel, camera = camera)
        Text(
            text = "◀ ▶ caméra · 1-8 accès direct · Retour : grille",
            color = CameraTvColors.TextMuted,
            fontSize = 18.sp,
        )
        if (camera?.ptz == true) {
            Text(
                text = "OK : piloter la caméra",
                color = CameraTvColors.Accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Bandeaux permanents du mode PTZ : badge et nom en haut, raccourcis en bas, croix discrète à droite. */
@Composable
private fun PtzOverlay(channel: Int, camera: Camera?) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(OverlayBackground, RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Mode PTZ",
                color = CameraTvColors.OnAccent,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(CameraTvColors.Accent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
            CameraTitle(channel = channel, camera = camera)
        }

        DirectionalCross(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(120.dp)
                .alpha(0.8f),
        )

        Text(
            text = "Flèches : orienter · CH+ / CH- : zoom · 1-9 : preset · OK / Retour : quitter",
            color = CameraTvColors.Text,
            fontSize = 22.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(OverlayBackground, RoundedCornerShape(8.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun CameraTitle(channel: Int, camera: Camera?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = channel.toString(),
            color = CameraTvColors.Accent,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = camera?.name ?: "Caméra $channel",
            color = CameraTvColors.Text,
            fontSize = 30.sp,
        )
    }
}

/** Croix directionnelle décorative : quatre flèches autour d'un cercle central (la touche OK). */
@Composable
private fun DirectionalCross(modifier: Modifier = Modifier) {
    Canvas(modifier.background(OverlayBackground, CircleShape)) {
        // Une seule flèche « haut », dessinée quatre fois en tournant d'un quart de tour.
        val arrow = Path().apply {
            moveTo(size.width * 0.50f, size.height * 0.10f)
            lineTo(size.width * 0.62f, size.height * 0.27f)
            lineTo(size.width * 0.38f, size.height * 0.27f)
            close()
        }
        for (angle in listOf(0f, 90f, 180f, 270f)) {
            rotate(angle) { drawPath(arrow, color = CameraTvColors.Text) }
        }
        drawCircle(
            color = CameraTvColors.Accent,
            radius = size.minDimension * 0.12f,
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}
