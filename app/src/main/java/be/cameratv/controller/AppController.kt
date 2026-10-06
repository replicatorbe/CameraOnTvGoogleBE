package be.cameratv.controller

import be.cameratv.model.AppModel
import be.cameratv.model.AppState
import be.cameratv.model.Camera
import be.cameratv.model.MqttConfig
import be.cameratv.model.NvrConfig
import be.cameratv.model.PtzDirection
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
import kotlinx.coroutines.delay
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

    // État du mouvement PTZ. Lu et modifié uniquement depuis le thread principal
    // (onCommand, onCommandReleased et le minuteur de sécurité, lancé dans [scope]).

    /** Direction en cours de mouvement, ou null si la caméra est immobile. */
    private var movingDirection: PtzDirection? = null

    /** Caméra qui bouge : c'est elle qu'il faut arrêter, même si l'écran a changé entre-temps. */
    private var movingCamera: Camera? = null

    /** Arrête le mouvement si aucun appui (ou répétition) n'arrive à temps : protège d'un relâchement perdu. */
    private var safetyTimer: Job? = null

    /** Durée minimale du mouvement en cours : un appui bref produit un petit déplacement visible. */
    private var minPulse: Job? = null

    /** Incrémenté à chaque appui : invalide un arrêt différé si la touche est de nouveau pressée. */
    private var pressCount = 0

    /** Dernier ordre PTZ confié au pilote ; chaque nouvel ordre attend la fin du précédent. */
    private var lastPtzOrder: Job? = null

    /** Au lancement : configuration enregistrée → connexion, sinon écran de configuration. */
    fun start() {
        launchExclusive {
            model.update { it.copy(screen = Screen.Loading, ptzMode = false, error = null) }
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

    // STUB : implémentation à venir (MVP 3).
    /** Commande de la domotique ; true si elle a été appliquée. */
    fun onExternalCommand(command: ExternalCommand): Boolean = TODO()

    /** Enregistre la configuration MQTT (null = désactivé) et la place dans l'état. */
    fun updateMqtt(config: MqttConfig?): Unit = TODO()

    /** La vue a mis l'application en arrière-plan suite à [AppState.exitRequested]. */
    fun onExitHandled(): Unit = TODO()

    /** Relâchement d'une touche dont l'appui a été consommé : arrête un mouvement PTZ en cours. */
    fun onCommandReleased(command: RemoteCommand) {
        val direction = ptzDirectionOf(command) ?: return
        if (direction != movingDirection) return
        val pulse = minPulse
        if (pulse == null || !pulse.isActive) {
            stopMovement()
            return
        }
        // Appui bref : on laisse la caméra bouger jusqu'à la durée minimale avant l'arrêt.
        val press = pressCount
        scope.launch {
            pulse.join()
            if (press == pressCount) stopMovement()
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
        stopMovement() // Avec l'ancien pilote, avant qu'il ne soit remplacé.
        connectJob?.cancel()
        connectJob = scope.launch(block = block)
    }

    private suspend fun connectNow(config: NvrConfig) {
        model.update { it.copy(screen = Screen.Loading, config = config, ptzMode = false, error = null) }
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
                    ptzMode = false,
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
        model.update { it.copy(screen = Screen.Setup, ptzMode = false, error = message) }
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
        val camera = cameras.getOrNull(index)
        if (current.ptzMode && camera != null) {
            onPtzCommand(command, camera)
            return true
        }
        when (command) {
            RemoteCommand.Right, RemoteCommand.ChannelUp -> showFullscreen(wrap(index + 1, cameras.size))
            RemoteCommand.Left, RemoteCommand.ChannelDown -> showFullscreen(wrap(index - 1, cameras.size))
            is RemoteCommand.Digit -> digitIndex(command, cameras.size)?.let { showFullscreen(it) }
            RemoteCommand.Back -> showGrid(index)
            RemoteCommand.Ok -> if (camera?.ptz == true) model.update { it.copy(ptzMode = true) }
            // Haut et bas ne servent qu'en mode PTZ : consommées, sans effet.
            RemoteCommand.Up, RemoteCommand.Down -> Unit
            RemoteCommand.Menu -> showSetup()
        }
        return true
    }

    /** Mode PTZ : flèches et CH+/CH- pilotent la caméra, les chiffres rappellent un préréglage. */
    private fun onPtzCommand(command: RemoteCommand, camera: Camera) {
        val direction = ptzDirectionOf(command)
        if (direction != null) {
            pressDirection(camera, direction)
            return
        }
        when (command) {
            is RemoteCommand.Digit -> if (command.value in 1..9) gotoPreset(camera, command.value)
            RemoteCommand.Ok, RemoteCommand.Back -> leavePtzMode()
            RemoteCommand.Menu -> showSetup()
            else -> Unit
        }
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
        stopMovement()
        model.update {
            val camera = it.cameras.getOrNull(index) ?: return@update it
            it.copy(screen = Screen.Fullscreen(camera.channel), focusedIndex = index, ptzMode = false)
        }
    }

    private fun showGrid(focusedIndex: Int) {
        stopMovement()
        model.update { it.copy(screen = Screen.Grid, focusedIndex = focusedIndex, ptzMode = false) }
    }

    private fun showSetup() {
        stopMovement()
        model.update { it.copy(screen = Screen.Setup, ptzMode = false, error = null) }
    }

    /** Touche 1 → index 0, etc. Null si aucune caméra ne correspond. */
    private fun digitIndex(digit: RemoteCommand.Digit, size: Int): Int? =
        (digit.value - 1).takeIf { digit.value in 1..size }

    private fun wrap(index: Int, size: Int): Int = if (size == 0) 0 else Math.floorMod(index, size)

    // --- PTZ -----------------------------------------------------------------------------------

    private fun ptzDirectionOf(command: RemoteCommand): PtzDirection? = when (command) {
        RemoteCommand.Up -> PtzDirection.UP
        RemoteCommand.Down -> PtzDirection.DOWN
        RemoteCommand.Left -> PtzDirection.LEFT
        RemoteCommand.Right -> PtzDirection.RIGHT
        RemoteCommand.ChannelUp -> PtzDirection.ZOOM_IN
        RemoteCommand.ChannelDown -> PtzDirection.ZOOM_OUT
        else -> null
    }

    /**
     * Appui (ou répétition automatique) sur une direction. Le premier appui démarre le mouvement ;
     * les répétitions de la même touche ne font que relancer le minuteur de sécurité.
     */
    private fun pressDirection(camera: Camera, direction: PtzDirection) {
        pressCount++
        if (direction != movingDirection || camera != movingCamera) {
            stopMovement()
            movingDirection = direction
            movingCamera = camera
            sendPtzOrder { it.ptzStart(camera, direction) }
            minPulse = scope.launch { delay(PTZ_MIN_PULSE_MS) }
        }
        restartSafetyTimer()
    }

    /** Arrête le mouvement en cours, s'il y en a un. Sans effet sinon. */
    private fun stopMovement() {
        val direction = movingDirection ?: return
        val camera = movingCamera ?: return
        safetyTimer?.cancel()
        safetyTimer = null
        movingDirection = null
        movingCamera = null
        sendPtzOrder { it.ptzStop(camera, direction) }
    }

    private fun restartSafetyTimer() {
        safetyTimer?.cancel()
        safetyTimer = scope.launch {
            delay(PTZ_SAFETY_TIMEOUT_MS)
            safetyTimer = null
            stopMovement()
        }
    }

    private fun gotoPreset(camera: Camera, preset: Int) {
        stopMovement()
        sendPtzOrder { it.gotoPreset(camera, preset) }
    }

    /** Quitte le mode PTZ en restant sur la même caméra en plein écran. */
    private fun leavePtzMode() {
        stopMovement()
        model.update { it.copy(ptzMode = false) }
    }

    /**
     * Confie un ordre au pilote, strictement après les ordres précédents : chaque ordre attend
     * la fin du précédent, un arrêt ne peut donc jamais doubler son démarrage. Le pilote est celui
     * du moment de l'appui. Une erreur du NVR est ignorée et ne bloque pas les ordres suivants.
     */
    private fun sendPtzOrder(order: suspend (CameraDriver) -> Unit) {
        val target = driver ?: return
        val previous = lastPtzOrder
        lastPtzOrder = scope.launch {
            previous?.join()
            try {
                order(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // NVR injoignable ou commande refusée : rien à afficher, l'appui suivant réessaiera.
            }
        }
    }

    private companion object {
        const val GENERIC_ERROR = "Connexion au NVR impossible"

        /** Couvre le délai avant la première répétition d'une touche maintenue (~500 ms). */
        const val PTZ_SAFETY_TIMEOUT_MS = 800L

        /** Mouvement minimal d'un appui bref : le dôme accélère lentement (mesuré sur SD5A, vitesse 4 : 150 ms ≈ 0,1°, 500 ms ≈ 2°). */
        const val PTZ_MIN_PULSE_MS = 500L
    }
}
