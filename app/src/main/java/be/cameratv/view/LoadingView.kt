package be.cameratv.view

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Écran d'attente pendant la connexion au NVR. */
@Composable
fun LoadingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spinner(size = 72.dp)
        Text("Connexion au NVR…", color = CameraTvColors.Text, fontSize = 30.sp)
    }
}

/**
 * Indicateur de progression circulaire indéterminé.
 * (tv-material 1.0 n'en fournit pas, et Material 3 « mobile » n'est pas une dépendance.)
 */
@Composable
fun Spinner(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = CameraTvColors.Accent,
    strokeWidth: Dp = size / 12,
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Canvas(modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        drawArc(
            color = color.copy(alpha = 0.2f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(stroke),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
