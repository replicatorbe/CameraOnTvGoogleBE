package be.cameratv.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Couleurs partagées par les vues. */
object CameraTvColors {
    val Background = Color(0xFF0A0A0C)
    val Surface = Color(0xFF1A1B20)
    val SurfaceVariant = Color(0xFF26272E)
    val Accent = Color(0xFF4FC3F7)
    val OnAccent = Color(0xFF00222E)
    val Text = Color(0xFFECECEF)
    val TextMuted = Color(0xFFA0A3AD)
    val Error = Color(0xFFFF6B6B)
}

private val DarkColors = darkColorScheme(
    primary = CameraTvColors.Accent,
    onPrimary = CameraTvColors.OnAccent,
    background = CameraTvColors.Background,
    onBackground = CameraTvColors.Text,
    surface = CameraTvColors.Surface,
    onSurface = CameraTvColors.Text,
    surfaceVariant = CameraTvColors.SurfaceVariant,
    onSurfaceVariant = CameraTvColors.TextMuted,
    error = CameraTvColors.Error,
)

/** Thème sombre « 10-foot UI » : fond quasi noir, accent cyan bien visible pour le focus. */
@Composable
fun CameraTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
