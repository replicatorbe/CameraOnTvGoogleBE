package be.cameratv.view

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private const val RETRY_DELAY_MS = 3_000L

private enum class PlayerStatus { Connecting, Playing, Error }

/**
 * Lecteur RTSP réutilisable (Media3 / ExoPlayer).
 *
 * - Un lecteur par [url] : il est recréé si l'URL change et libéré quand le composable disparaît.
 * - Libéré aussi quand l'activité passe en arrière-plan (ON_STOP) pour rendre les décodeurs
 *   matériels, puis recréé au retour (ON_START).
 * - En cas d'erreur, nouvelle tentative automatique après 3 s.
 * - [onPlayingChange] : true à la première image, false en cas d'erreur (la grille s'en sert pour
 *   garder le snapshot affiché tant que le direct n'est pas prêt).
 */
@Composable
fun RtspPlayer(
    url: String?,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onPlayingChange: (Boolean) -> Unit = {},
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (url == null) {
            StatusLabel("Aucun flux")
            return@Box
        }

        val context = LocalContext.current
        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

        // Incrémenté à chaque nouvelle tentative : force la création d'un nouveau lecteur.
        var attempt by remember(url) { mutableIntStateOf(0) }
        var status by remember(url) { mutableStateOf(PlayerStatus.Connecting) }

        val player: ExoPlayer? = remember(url, started, attempt) {
            if (started) createRtspPlayer(context, url, muted) else null
        }

        DisposableEffect(player) {
            if (player == null) return@DisposableEffect onDispose { }
            status = PlayerStatus.Connecting
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    status = PlayerStatus.Playing
                    onPlayingChange(true)
                }

                override fun onPlayerError(error: PlaybackException) {
                    status = PlayerStatus.Error
                    onPlayingChange(false)
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }

        LaunchedEffect(player, muted) {
            player?.volume = if (muted) 0f else 1f
        }

        // Relance automatique ; annulée si le composable disparaît ou si l'état change.
        LaunchedEffect(status, player) {
            if (status == PlayerStatus.Error && player != null) {
                delay(RETRY_DELAY_MS)
                attempt++
            }
        }

        AndroidView(
            factory = { ctx -> createPlayerView(ctx) },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        when (status) {
            PlayerStatus.Connecting -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spinner(size = 36.dp)
                StatusLabel("Connexion…")
            }
            PlayerStatus.Error -> StatusLabel("Flux indisponible")
            PlayerStatus.Playing -> Unit
        }
    }
}

@Composable
private fun StatusLabel(text: String) {
    Text(text, color = CameraTvColors.TextMuted, fontSize = 20.sp)
}

@OptIn(UnstableApi::class)
private fun createRtspPlayer(
    context: Context,
    url: String,
    muted: Boolean,
): ExoPlayer {
    // Petits tampons : on privilégie la latence (vidéosurveillance en direct).
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 500,
            /* maxBufferMs = */ 2_000,
            /* bufferForPlaybackMs = */ 250,
            /* bufferForPlaybackAfterRebufferMs = */ 500,
        )
        .build()

    // RTP sur TCP : bien plus fiable que l'UDP sur le Wi-Fi d'une TV.
    val mediaSource = RtspMediaSource.Factory()
        .setForceUseRtpTcp(true)
        .createMediaSource(MediaItem.fromUri(url))

    val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

    return ExoPlayer.Builder(context, renderersFactory)
        .setLoadControl(loadControl)
        .build()
        .apply {
            // Tuile muette : inutile de décoder la piste audio.
            if (muted) {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
            setMediaSource(mediaSource)
            playWhenReady = true
            prepare()
        }
}

@OptIn(UnstableApi::class)
private fun createPlayerView(context: Context): PlayerView =
    // PlayerView utilise une SurfaceView par défaut : le chemin le plus économe pour le décodeur.
    PlayerView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(AndroidColor.BLACK)
        setKeepContentOnPlayerReset(false)
        isFocusable = false
        isFocusableInTouchMode = false
    }
