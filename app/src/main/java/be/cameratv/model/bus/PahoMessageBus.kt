package be.cameratv.model.bus

import be.cameratv.model.MqttConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

// STUB : implémentation à venir. Le constructeur public est figé.
class PahoMessageBus(private val clientId: String) : MessageBus {
    override val connected: StateFlow<Boolean> = MutableStateFlow(false)
    override val messages: Flow<BusMessage> = emptyFlow()
    override fun start(config: MqttConfig, subscriptions: List<String>, will: BusMessage): Unit = TODO()
    override fun publish(message: BusMessage): Unit = TODO()
    override fun stop(farewell: BusMessage?): Unit = TODO()
}
