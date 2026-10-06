package be.cameratv.controller

import be.cameratv.model.AppModel
import be.cameratv.model.AppState
import be.cameratv.model.Camera
import be.cameratv.model.NvrConfig
import be.cameratv.model.Screen
import be.cameratv.model.SettingsRepository
import be.cameratv.model.StreamQuality
import be.cameratv.model.driver.AuthenticationException
import be.cameratv.model.driver.CameraDriver
import be.cameratv.model.driver.CameraDriverException
import be.cameratv.model.driver.CameraDriverFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Le Contrôleur du MVC : interprète les commandes de la télécommande, pilote le NVR
 * et met à jour le [AppModel]. Il ne touche jamais à l'interface Android.
 */
class AppController(
    private val model: AppModel,
    private val settings: SettingsRepository,
    private val driverFactory: CameraDriverFactory,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<AppState> = model.state

    /** Pilote de la dernière connexion réussie (null tant qu'aucune connexion n'a abouti). */
    @Volatile
    private var driver: CameraDriver? = null

    /** Chargement ou connexion en cours ; annulé si une nouvelle connexion est demandée. */
    private var connectJob: Job? = null

    /** Au lancement : configuration enregistrée → connexion, sinon écran de configuration. */
    fun start() {
        launchExclusive {
            model.update { it.copy(screen = Screen.Loading, error = null) }
            val config = try {
                settings.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // Configuration illisible : on repart du formulaire.
            }
            if (config == null) {
                model.update { it.copy(screen = Screen.Setup) }
            } else {
                connectNow(config)
            }
        }
    }

    fun submitSetup(config: NvrConfig) = connect(config)

    fun retry() {
        state.value.config?.let { connect(it) }
    }

    /** Retourne true si la commande a été traitée (l'activité consomme alors la touche). */
    fun onCommand(command: RemoteCommand): Boolean {
        val current = state.value
        return when (val screen = current.screen) {
            Screen.Grid -> onGridCommand(command, current)
            is Screen.Fullscreen -> onFullscreenCommand(command, current, screen.channel)
            Screen.Setup -> onSetupCommand(command, current)
            Screen.Loading -> false
        }
    }

    fun streamUrl(camera: Camera, quality: StreamQuality): String? =
        driver?.streamUrl(camera, quality)

    /** Image JPEG instantanée, ou null si indisponible (la vue garde alors l'image précédente). */
    suspend fun snapshot(camera: Camera): ByteArray? {
        val current = driver ?: return null
        return try {
            current.snapshot(camera)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // --- Connexion ---------------------------------------------------------------------------

    private fun connect(config: NvrConfig) {
        launchExclusive { connectNow(config) }
    }

    /** Annule le travail en cours puis lance [block] : une seule connexion à la fois. */
    private fun launchExclusive(block: suspend CoroutineScope.() -> Unit) {
        connectJob?.cancel()
        connectJob = scope.launch(block = block)
    }

    private suspend fun connectNow(config: NvrConfig) {
        model.update { it.copy(screen = Screen.Loading, config = config, error = null) }
        try {
            val newDriver = driverFactory.create(config)
            val cameras = newDriver.listCameras()
            // Une connexion annulée entre-temps ne doit pas écraser l'état.
            currentCoroutineContext().ensureActive()
            if (cameras.isEmpty()) {
                showSetupError("Aucune caméra trouvée sur le NVR")
                return
            }
            driver = newDriver
            model.update {
                it.copy(
                    screen = Screen.Grid,
                    cameras = cameras,
                    focusedIndex = it.focusedIndex.coerceIn(0, cameras.lastIndex),
                    error = null,
                )
            }
            saveConfig(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthenticationException) {
            showSetupError("Identifiants refusés par le NVR")
        } catch (e: CameraDriverException) {
            showSetupError(e.message ?: GENERIC_ERROR)
        } catch (e: Exception) {
            showSetupError(GENERIC_ERROR)
        }
    }

    /** La configuration n'est enregistrée qu'après une connexion réussie. */
    private suspend fun saveConfig(config: NvrConfig) {
        try {
            settings.save(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Les caméras s'affichent quand même ; la saisie sera simplement redemandée au prochain lancement.
        }
    }

    private fun showSetupError(message: String) {
        model.update { it.copy(screen = Screen.Setup, error = message) }
    }

    // --- Commandes par écran -----------------------------------------------------------------

    private fun onGridCommand(command: RemoteCommand, current: AppState): Boolean {
        val cameras = current.cameras
        if (cameras.isEmpty()) return command != RemoteCommand.Back
        val index = current.focusedIndex
        val columns = current.gridColumns
        when (command) {
            RemoteCommand.Up -> moveFocusTo(index - columns, cameras.size)
            RemoteCommand.Down -> moveFocusTo(index + columns, cameras.size)
            RemoteCommand.Left -> moveFocusTo((index - 1).coerceAtLeast(0), cameras.size)
            RemoteCommand.Right -> moveFocusTo((index + 1).coerceAtMost(cameras.lastIndex), cameras.size)
            RemoteCommand.ChannelUp -> moveFocusTo(wrap(index + 1, cameras.size), cameras.size)
            RemoteCommand.ChannelDown -> moveFocusTo(wrap(index - 1, cameras.size), cameras.size)
            RemoteCommand.Ok -> showFullscreen(index.coerceIn(0, cameras.lastIndex))
            is RemoteCommand.Digit -> digitIndex(command, cameras.size)?.let { showFullscreen(it) }
            RemoteCommand.Menu -> showSetup()
            RemoteCommand.Back -> return false // Comportement par défaut de l'activité : quitter l'app.
        }
        return true
    }

    private fun onFullscreenCommand(command: RemoteCommand, current: AppState, channel: Int): Boolean {
        val cameras = current.cameras
        val index = cameras.indexOfFirst { it.channel == channel }.coerceAtLeast(0)
        when (command) {
            RemoteCommand.Right, RemoteCommand.ChannelUp -> showFullscreen(wrap(index + 1, cameras.size))
            RemoteCommand.Left, RemoteCommand.ChannelDown -> showFullscreen(wrap(index - 1, cameras.size))
            is RemoteCommand.Digit -> digitIndex(command, cameras.size)?.let { showFullscreen(it) }
            RemoteCommand.Back -> model.update { it.copy(screen = Screen.Grid, focusedIndex = index) }
            // Réservées au mode PTZ (jalon suivant) : consommées pour l'instant, sans effet.
            RemoteCommand.Up, RemoteCommand.Down, RemoteCommand.Ok -> Unit
            RemoteCommand.Menu -> showSetup()
        }
        return true
    }

    /** Le formulaire Compose gère lui-même focus et saisie ; seul Retour nous intéresse. */
    private fun onSetupCommand(command: RemoteCommand, current: AppState): Boolean {
        if (command != RemoteCommand.Back || current.cameras.isEmpty()) return false
        model.update { it.copy(screen = Screen.Grid, error = null) }
        return true
    }

    /** Déplace le focus si [target] est dans la grille, sinon ne fait rien. */
    private fun moveFocusTo(target: Int, size: Int) {
        if (target in 0 until size) model.update { it.copy(focusedIndex = target) }
    }

    /** Affiche la caméra d'index [index] en plein écran et y aligne le focus de la grille. */
    private fun showFullscreen(index: Int) {
        model.update {
            val camera = it.cameras.getOrNull(index) ?: return@update it
            it.copy(screen = Screen.Fullscreen(camera.channel), focusedIndex = index)
        }
    }

    private fun showSetup() {
        model.update { it.copy(screen = Screen.Setup, error = null) }
    }

    /** Touche 1 → index 0, etc. Null si aucune caméra ne correspond. */
    private fun digitIndex(digit: RemoteCommand.Digit, size: Int): Int? =
        (digit.value - 1).takeIf { digit.value in 1..size }

    private fun wrap(index: Int, size: Int): Int = if (size == 0) 0 else Math.floorMod(index, size)

    private companion object {
        const val GENERIC_ERROR = "Connexion au NVR impossible"
    }
}
