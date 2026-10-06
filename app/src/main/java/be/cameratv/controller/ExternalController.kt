package be.cameratv.controller

import be.cameratv.model.AppModel
import be.cameratv.model.AppState
import be.cameratv.model.MqttConfig
import be.cameratv.model.Screen
import be.cameratv.model.bus.BusMessage
import be.cameratv.model.bus.MessageBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Contrôleur des commandes externes : relaie les messages MQTT vers [AppController] et publie
 * l'état de l'application. Suit `state.mqtt` : (re)connexion quand la configuration change.
 *
 * Topics (préfixe `<base>` = [MqttConfig.baseTopic]) :
 * - reçus : `<base>/cmd/show`, `<base>/cmd/grid`, `<base>/cmd/ptz`, `<base>/cmd/exit` ;
 * - publiés (conservés par le broker) : `<base>/online` (`true`/`false`) et `<base>/state` (JSON).
 */
class ExternalController(
    private val model: AppModel,
    private val appController: AppController,
    private val bus: MessageBus,
    private val scope: CoroutineScope,
) {
    /** Collecteurs actifs entre [start] et [stop]. */
    private var job: Job? = null

    /** Configuration avec laquelle le bus a été démarré (null = bus arrêté). */
    private var active: MqttConfig? = null

    /** Appelé quand l'application passe au premier plan. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            launch {
                model.state.map { it.mqtt }.distinctUntilChanged().collect { applyConfig(it) }
            }
            launch {
                var wasConnected = false
                bus.connected.collect { connected ->
                    model.update { it.copy(mqttConnected = connected) }
                    if (connected && !wasConnected) announce()
                    wasConnected = connected
                }
            }
            launch {
                bus.messages.collect { onMessage(it) }
            }
            launch {
                // L'état initial est publié par announce() à la connexion ; ici, uniquement les changements.
                model.state.map { snapshotOf(it) }.distinctUntilChanged().drop(1).collect { publishState(it) }
            }
        }
    }

    /** Appelé quand l'application passe en arrière-plan : publie l'état hors ligne et se déconnecte. */
    fun stop() {
        job?.cancel()
        job = null
        active?.let { bus.stop(offline(it)) }
        active = null
        model.update { it.copy(mqttConnected = false) }
    }

    private fun applyConfig(config: MqttConfig?) {
        // Un changement de broker ou de préfixe impose de quitter proprement l'ancienne session.
        active?.let { bus.stop(offline(it)) }
        active = config
        if (config != null) {
            bus.start(config, listOf("${config.baseTopic}/cmd/#"), offline(config))
        }
    }

    /** À chaque (re)connexion : présence et état courant, que le broker conserve. */
    private fun announce() {
        val config = active ?: return
        bus.publish(BusMessage("${config.baseTopic}/online", "true", retained = true))
        publishState(snapshotOf(model.state.value))
    }

    private fun onMessage(message: BusMessage) {
        val config = active ?: return
        val prefix = "${config.baseTopic}/cmd/"
        if (!message.topic.startsWith(prefix)) return
        val command = message.topic.removePrefix(prefix)
        if (command.isEmpty() || '/' in command) return
        ExternalCommandParser.parse(command, message.payload)?.let { appController.onExternalCommand(it) }
    }

    private fun publishState(snapshot: StateSnapshot) {
        val config = active ?: return
        if (!bus.connected.value) return
        bus.publish(BusMessage("${config.baseTopic}/state", json.encodeToString(snapshot), retained = true))
    }

    private fun offline(config: MqttConfig) = BusMessage("${config.baseTopic}/online", "false", retained = true)

    private fun snapshotOf(state: AppState): StateSnapshot {
        val fullscreen = state.screen as? Screen.Fullscreen
        return StateSnapshot(
            screen = when (state.screen) {
                Screen.Setup -> "setup"
                Screen.Loading -> "loading"
                Screen.Grid -> "grid"
                is Screen.Fullscreen -> "fullscreen"
            },
            camera = fullscreen?.channel,
            cameraName = fullscreen?.let { screen -> state.cameras.firstOrNull { it.channel == screen.channel }?.name },
            ptzMode = state.ptzMode,
            cameras = state.cameras.map { CameraSnapshot(it.channel, it.name, it.ptz) },
        )
    }

    /** Contenu de `<base>/state`. */
    @Serializable
    internal data class StateSnapshot(
        val screen: String,
        val camera: Int?,
        val cameraName: String?,
        val ptzMode: Boolean,
        val cameras: List<CameraSnapshot>,
    )

    @Serializable
    internal data class CameraSnapshot(val channel: Int, val name: String, val ptz: Boolean)

    private companion object {
        val json = Json
    }
}
