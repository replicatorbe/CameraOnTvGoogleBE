package be.cameratv.view

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.cameratv.controller.AppController
import be.cameratv.model.AppState
import be.cameratv.model.StreamQuality
import kotlinx.coroutines.delay

private const val OVERLAY_DURATION_MS = 3_000L

/** Une caméra en plein écran (flux principal, son actif) avec un bandeau d'information temporaire. */
@Composable
fun FullscreenView(state: AppState, channel: Int, controller: AppController) {
    val camera = state.cameras.firstOrNull { it.channel == channel }
    val url = camera?.let { controller.streamUrl(it, StreamQuality.MAIN) }

    // Le bandeau réapparaît à chaque changement de caméra, puis s'efface.
    var overlayVisible by remember { mutableStateOf(true) }
    LaunchedEffect(channel) {
        overlayVisible = true
        delay(OVERLAY_DURATION_MS)
        overlayVisible = false
    }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (overlayVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "overlayAlpha",
    )

    Box(Modifier.fillMaxSize()) {
        RtspPlayer(url = url, muted = false, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(32.dp)
                .alpha(overlayAlpha)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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
            Text(
                text = "◀ ▶ caméra · 1-8 accès direct · Retour : grille",
                color = CameraTvColors.TextMuted,
                fontSize = 18.sp,
            )
        }
    }
}
