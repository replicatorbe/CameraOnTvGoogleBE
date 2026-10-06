package be.cameratv.model.bus

import be.cameratv.model.MqttConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class BusMessage(val topic: String, val payload: String, val retained: Boolean = false)

/**
 * Bus de messages vers la domotique (MQTT). La reconnexion est gérée par l'implémentation :
 * après [start], elle se reconnecte seule et se réabonne jusqu'à [stop].
 */
interface MessageBus {
    val connected: StateFlow<Boolean>

    /** Messages reçus sur les abonnements. */
    val messages: Flow<BusMessage>

    /** [will] est publié par le broker si la connexion est perdue sans [stop]. */
    fun start(config: MqttConfig, subscriptions: List<String>, will: BusMessage)

    /** Ignoré (sans erreur) si le bus n'est pas connecté. */
    fun publish(message: BusMessage)

    /** Publie [farewell] s'il est fourni, puis se déconnecte proprement. */
    fun stop(farewell: BusMessage? = null)
}
