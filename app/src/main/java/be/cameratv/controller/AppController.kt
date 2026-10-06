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

    // État des commandes externes (domotique, liens profonds), lui aussi confiné au thread principal.

    /** Dernière commande reçue pendant la connexion ; appliquée dès que les caméras sont connues. */
    private var pendingExternal: ExternalCommand? = null

    /** Mouvements PTZ commandés de l'extérieur, par canal : direction et arrêt programmé. */
    private val externalMoves = mutableMapOf<Int, ExternalMove>()

    /** Écran à retrouver après un affichage temporaire, et le minuteur qui l'y ramène. */
    private var returnTarget: ReturnTarget? = null
    private var returnTimer: Job? = null

    /** Incrémenté par [updateMqtt] : un chargement plus ancien ne doit pas écraser un choix récent. */
    private var mqttRevision = 0

    /** Au lancement : configuration enregistrée → connexion, sinon écran de configuration. */
    fun start() {
        loadMqtt()
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
                pendingExternal = null
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
        val handled = when (val screen = current.screen) {
            Screen.Grid -> onGridCommand(command, current)
            is Screen.Fullscreen -> onFullscreenCommand(command, current, screen.channel)
            Screen.Setup -> onSetupCommand(command, current)
            Screen.Loading -> false
        }
        // L'utilisateur a repris la main : l'affichage temporaire devient définitif.
        if (handled) cancelAutoReturn()
        return handled
    }

    /**
     * Commande de la domotique ; true si elle a été appliquée (ou mise en attente pendant la
     * connexion). Ignorée sur l'écran de configuration ou si la caméra est inconnue.
     */
    fun onExternalCommand(command: ExternalCommand): Boolean {
        val current = state.value
        return when (current.screen) {
            Screen.Setup -> false
            Screen.Loading -> {
                pendingExternal = command
                true
            }
            Screen.Grid, is Screen.Fullscreen -> applyExternal(command, current)
        }
    }

    /** Enregistre la configuration MQTT (null = désactivé) et la place dans l'état. */
    fun updateMqtt(config: MqttConfig?) {
        mqttRevision++
        model.update { it.copy(mqtt = config) }
        scope.launch {
            try {
                settings.saveMqtt(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Le pilotage fonctionne pour cette session ; il faudra le ressaisir au prochain lancement.
            }
        }
    }

    /** La vue a mis l'application en arrière-plan suite à [AppState.exitRequested]. */
    fun onExitHandled() {
        model.update { it.copy(exitRequested = false) }
    }

    /** La TV allume ou éteint son écran (sortie ou entrée en veille). */
    fun onScreenChanged(on: Boolean) {
        model.update { it.copy(screenOn = on) }
    }

    /** L'écran de l'application devient visible ou passe derrière une autre application. */
    fun onUiVisibilityChanged(visible: Boolean) {
        model.update {
            it.copy(uiVisible = visible, foregroundRequested = it.foregroundRequested && !visible)
        }
    }

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
        // Avec l'ancien pilote, avant qu'il ne soit remplacé.
        stopMovement()
        stopAllExternalMoves()
        cancelAutoReturn()
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
            pendingExternal?.let { command ->
                pendingExternal = null
                applyExternal(command, state.value)
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
        pendingExternal = null
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
        cancelAutoReturn()
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
            stopExternalMove(camera.channel) // La télécommande reprend la main sur cette caméra.
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

    // --- Commandes externes -------------------------------------------------------------------

    private fun applyExternal(command: ExternalCommand, current: AppState): Boolean = when (command) {
        is ExternalCommand.ShowCamera -> showCameraExternally(command, current).also { shown ->
            if (shown) requestForegroundIfHidden()
        }
        ExternalCommand.ShowGrid -> {
            cancelAutoReturn()
            showGrid(current.focusedIndex)
            requestForegroundIfHidden()
            true
        }
        is ExternalCommand.Ptz -> ptzExternally(command, current)
        ExternalCommand.Exit -> {
            stopMovement()
            stopAllExternalMoves()
            cancelAutoReturn()
            // Déjà en arrière-plan : rien à quitter.
            model.update { it.copy(ptzMode = false, exitRequested = it.uiVisible, foregroundRequested = false) }
            true
        }
    }

    /** Application en arrière-plan (film, autre app) : elle demande à repasser au premier plan. */
    private fun requestForegroundIfHidden() {
        if (!state.value.uiVisible) model.update { it.copy(foregroundRequested = true) }
    }

    /** Index de la caméra désignée, ou -1 si elle est inconnue. */
    private fun resolve(ref: CameraRef, cameras: List<Camera>): Int = when (ref) {
        is CameraRef.ByChannel -> cameras.indexOfFirst { it.channel == ref.channel }
        is CameraRef.ByName -> cameras.indexOfFirst { it.name.trim().equals(ref.name.trim(), ignoreCase = true) }
    }

    private fun showCameraExternally(command: ExternalCommand.ShowCamera, current: AppState): Boolean {
        val index = resolve(command.camera, current.cameras)
        if (index < 0) return false
        val target = Screen.Fullscreen(current.cameras[index].channel)
        val duration = command.durationSec ?: 0
        // Un affichage temporaire déjà en cours garde l'écran d'origine.
        val previous = returnTarget ?: ReturnTarget(current.screen, current.focusedIndex, background = !current.uiVisible)
        cancelAutoReturn()
        if (duration > 0 && (previous.screen != target || previous.background)) {
            returnTarget = previous
            returnTimer = scope.launch {
                delay(duration * 1000L)
                returnTimer = null
                returnTarget = null
                restore(previous)
            }
        }
        showFullscreen(index)
        return true
    }

    /**
     * Fin d'un affichage temporaire : retour à la grille ou à la caméra affichée avant.
     * Si l'application avait été tirée de l'arrière-plan, elle y retourne (le film reprend).
     */
    private fun restore(previous: ReturnTarget) {
        if (previous.background) {
            model.update { it.copy(exitRequested = it.uiVisible, foregroundRequested = false) }
        }
        val cameras = state.value.cameras
        if (cameras.isEmpty()) return
        val screen = previous.screen
        if (screen is Screen.Fullscreen) {
            val index = cameras.indexOfFirst { it.channel == screen.channel }
            if (index >= 0) {
                showFullscreen(index)
                return
            }
        }
        showGrid(previous.focusedIndex.coerceIn(0, cameras.lastIndex))
    }

    private fun cancelAutoReturn() {
        returnTimer?.cancel()
        returnTimer = null
        returnTarget = null
    }

    /** Pilote une caméra motorisée, affichée ou non, sans changer d'écran. */
    private fun ptzExternally(command: ExternalCommand.Ptz, current: AppState): Boolean {
        val camera = current.cameras.getOrNull(resolve(command.camera, current.cameras)) ?: return false
        if (!camera.ptz) return false
        // Tout mouvement en cours sur cette caméra (télécommande ou commande externe) s'arrête d'abord.
        if (movingCamera?.channel == camera.channel) stopMovement()
        stopExternalMove(camera.channel)
        when (val action = command.action) {
            is PtzAction.Move -> {
                val direction = action.direction
                sendPtzOrder { it.ptzStart(camera, direction) }
                val move = ExternalMove(camera, direction)
                externalMoves[camera.channel] = move
                move.stopTimer = scope.launch {
                    delay(action.durationMs)
                    if (externalMoves[camera.channel] === move) stopExternalMove(camera.channel)
                }
            }
            is PtzAction.Preset -> sendPtzOrder { it.gotoPreset(camera, action.preset) }
            PtzAction.Stop -> Unit
        }
        return true
    }

    private fun stopExternalMove(channel: Int) {
        val move = externalMoves.remove(channel) ?: return
        move.stopTimer?.cancel()
        sendPtzOrder { it.ptzStop(move.camera, move.direction) }
    }

    private fun stopAllExternalMoves() {
        externalMoves.keys.toList().forEach { stopExternalMove(it) }
    }

    /** Au lancement, que le NVR soit configuré ou non. */
    private fun loadMqtt() {
        val revision = mqttRevision
        scope.launch {
            val config = try {
                settings.loadMqtt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // Configuration illisible : pilotage désactivé.
            }
            if (revision == mqttRevision) model.update { it.copy(mqtt = config) }
        }
    }

    private class ExternalMove(val camera: Camera, val direction: PtzDirection) {
        var stopTimer: Job? = null
    }

    /** [background] : l'application était en arrière-plan avant l'affichage temporaire. */
    private data class ReturnTarget(val screen: Screen, val focusedIndex: Int, val background: Boolean = false)

    private companion object {
        const val GENERIC_ERROR = "Connexion au NVR impossible"

        /** Couvre le délai avant la première répétition d'une touche maintenue (~500 ms). */
        const val PTZ_SAFETY_TIMEOUT_MS = 800L

        /** Mouvement minimal d'un appui bref : le dôme accélère lentement (mesuré sur SD5A, vitesse 4 : 150 ms ≈ 0,1°, 500 ms ≈ 2°). */
        const val PTZ_MIN_PULSE_MS = 500L
    }
}
