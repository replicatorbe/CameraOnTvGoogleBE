package be.cameratv.view

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import be.cameratv.controller.AppController
import be.cameratv.model.Camera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val REFRESH_MS = 2_000L
/** Largeur visée après décodage : une tuile de grille fait ~630 px sur un écran 1080p. */
private const val TARGET_WIDTH_PX = 640

/**
 * Image fixe d'une caméra, rafraîchie toutes les 2 s tant que l'écran est visible.
 * Ne consomme aucun décodeur vidéo : la TCL n'en a que 2, réservés au direct.
 */
@Composable
fun SnapshotImage(
    camera: Camera,
    controller: AppController,
    modifier: Modifier = Modifier,
    initialDelayMs: Long = 0,
) {
    var image by remember(camera.channel) { mutableStateOf<ImageBitmap?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(camera.channel) {
        // Décalage initial : évite que toutes les tuiles interrogent le NVR au même instant.
        delay(initialDelayMs)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val bytes = controller.snapshot(camera)
                val decoded = bytes?.let { withContext(Dispatchers.Default) { decodeDownsampled(it) } }
                if (decoded != null) image = decoded.asImageBitmap()
                delay(REFRESH_MS)
            }
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val current = image
        if (current != null) {
            Image(current, contentDescription = camera.name, contentScale = ContentScale.Fit)
        } else {
            Text("Chargement…", color = CameraTvColors.TextMuted, fontSize = 18.sp)
        }
    }
}

/** Les snapshots du NVR sont en 2560x1440 : on décode directement à taille de tuile. */
private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= TARGET_WIDTH_PX) sampleSize *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
