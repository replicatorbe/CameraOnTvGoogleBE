package be.cameratv.controller

import be.cameratv.model.AppModel
import be.cameratv.model.bus.MessageBus
import kotlinx.coroutines.CoroutineScope

// STUB : implémentation à venir. Les signatures publiques sont figées.
/**
 * Contrôleur des commandes externes : relaie les messages MQTT vers [AppController] et publie
 * l'état de l'application. Suit `state.mqtt` : (re)connexion quand la configuration change.
 */
class ExternalController(
    private val model: AppModel,
    private val appController: AppController,
    private val bus: MessageBus,
    private val scope: CoroutineScope,
) {
    /** Appelé quand l'application passe au premier plan. */
    fun start(): Unit = TODO()

    /** Appelé quand l'application passe en arrière-plan : publie l'état hors ligne et se déconnecte. */
    fun stop(): Unit = TODO()
}
