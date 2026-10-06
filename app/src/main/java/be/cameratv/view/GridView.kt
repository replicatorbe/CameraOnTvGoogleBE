package be.cameratv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.cameratv.controller.AppController
import be.cameratv.model.AppState
import be.cameratv.model.Camera
import be.cameratv.model.StreamQuality
import kotlinx.coroutines.delay

private const val MAX_TILES = 9
private val Gap = 6.dp
private val FocusBorder = 4.dp
/** Le direct ne démarre que si le focus reste sur la tuile : pas de lecteur créé à chaque flèche. */
private const val LIVE_DELAY_MS = 600L
/** Décalage entre tuiles pour étaler les requêtes de snapshot vers le NVR. */
private const val SNAPSHOT_STAGGER_MS = 250L

/**
 * Mosaïque de caméras, sans défilement : lignes × [AppState.gridColumns], tuiles 16:9.
 * Le focus est dessiné à partir de [AppState.focusedIndex] (pas de focus Compose).
 *
 * Mode hybride : la TCL n'a que 2 décodeurs vidéo matériels. Seule la tuile sélectionnée est
 * en direct ; les autres affichent un snapshot rafraîchi.
 */
@Composable
fun GridView(state: AppState, controller: AppController) {
    val cameras = state.cameras.take(MAX_TILES)
    if (cameras.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Aucune caméra trouvée", color = CameraTvColors.TextMuted, fontSize = 28.sp)
        }
        return
    }

    val columns = state.gridColumns.coerceAtLeast(1)
    val rows = (cameras.size + columns - 1) / columns

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(Gap),
        contentAlignment = Alignment.Center,
    ) {
        // Plus grande tuile 16:9 qui tient dans l'écran pour ce nombre de lignes/colonnes.
        val widthPerTile = (maxWidth - Gap * (columns - 1)) / columns
        val heightPerTile = (maxHeight - Gap * (rows - 1)) / rows
        val tileWidth = min(widthPerTile, heightPerTile * 16f / 9f)
        val tileHeight = tileWidth * 9f / 16f

        Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
            cameras.chunked(columns).forEachIndexed { rowIndex, rowCameras ->
                Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                    rowCameras.forEachIndexed { columnIndex, camera ->
                        val index = rowIndex * columns + columnIndex
                        key(camera.channel) {
                            CameraTile(
                                camera = camera,
                                index = index,
                                controller = controller,
                                focused = index == state.focusedIndex,
                                modifier = Modifier.size(tileWidth, tileHeight),
                            )
                        }
                    }
                    // Dernière ligne incomplète : on garde l'alignement de la grille.
                    repeat(columns - rowCameras.size) {
                        Spacer(Modifier.size(tileWidth, tileHeight))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraTile(
    camera: Camera,
    index: Int,
    controller: AppController,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    var live by remember(focused) { mutableStateOf(false) }
    var livePlaying by remember(focused) { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) {
            delay(LIVE_DELAY_MS)
            live = true
        }
    }

    // La bordure est dessinée autour de la vidéo (padding) pour ne pas être masquée par la SurfaceView.
    Box(
        modifier
            .border(
                width = FocusBorder,
                color = if (focused) CameraTvColors.Accent else Color.Transparent,
                shape = shape,
            )
            .padding(FocusBorder)
    ) {
        if (live) {
            RtspPlayer(
                url = controller.streamUrl(camera, StreamQuality.SUB),
                muted = true,
                modifier = Modifier.fillMaxSize(),
                onPlayingChange = { livePlaying = it },
            )
        }
        // Au-dessus du lecteur tant que le direct n'a pas sa première image : jamais d'écran noir.
        // Masqué (et non retiré) pendant le direct, pour garder l'image au retour en snapshot.
        SnapshotImage(
            camera = camera,
            controller = controller,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (livePlaying) 0f else 1f),
            initialDelayMs = index * SNAPSHOT_STAGGER_MS,
        )
        CameraLabel(
            camera = camera,
            focused = focused,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        )
    }
}

@Composable
private fun CameraLabel(camera: Camera, focused: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                color = if (focused) CameraTvColors.Accent else Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val textColor = if (focused) CameraTvColors.OnAccent else CameraTvColors.Text
        Text(
            text = camera.channel.toString(),
            color = textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = camera.name,
            color = textColor,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Le nom se tronque en premier : la pastille PTZ reste visible sur les petites tuiles.
            modifier = Modifier.weight(1f, fill = false),
        )
        if (camera.ptz) PtzChip(onAccent = focused)
    }
}

/** Petite pastille « PTZ » : caméra motorisée, pilotable en plein écran. */
@Composable
private fun PtzChip(onAccent: Boolean) {
    Text(
        text = "PTZ",
        // Couleurs inversées quand l'étiquette elle-même est sur fond accent (tuile sélectionnée).
        color = if (onAccent) CameraTvColors.Accent else CameraTvColors.OnAccent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .background(
                color = if (onAccent) CameraTvColors.OnAccent else CameraTvColors.Accent,
                shape = RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
