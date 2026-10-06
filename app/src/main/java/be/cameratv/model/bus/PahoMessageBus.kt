package be.cameratv.model.bus

import be.cameratv.model.MqttConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * [MessageBus] MQTT basé sur le client asynchrone Paho (sans service Android).
 *
 * Fils d'exécution : [start] et [stop] sont sérialisés par un verrou ; les rappels Paho arrivent
 * sur les fils internes de Paho et ne prennent jamais ce verrou (ils vérifient seulement que leur
 * session est toujours la session courante), ce qui évite tout interblocage pendant [stop].
 *
 * Connexion initiale : la reconnexion automatique de Paho n'agit qu'après une première connexion
 * réussie ; tant que celle-ci n'a pas abouti, une nouvelle tentative est planifiée toutes les
 * [retryDelayMillis] ms jusqu'au succès ou jusqu'à [stop].
 */
class PahoMessageBus internal constructor(
    private val clientId: String,
    private val retryDelayMillis: Long,
) : MessageBus {

    constructor(clientId: String) : this(clientId, DEFAULT_RETRY_DELAY_MILLIS)

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _messages = MutableSharedFlow<BusMessage>(extraBufferCapacity = 64)
    override val messages: Flow<BusMessage> = _messages.asSharedFlow()

    private val lock = Any()

    @Volatile
    private var session: Session? = null

    /** Paramètres du dernier [start], pour [reconnectNow]. */
    private var lastStart: Triple<MqttConfig, List<String>, BusMessage>? = null

    /** Options de la session courante (exposées pour les tests). */
    internal val currentOptions: MqttConnectOptions? get() = session?.options

    override fun start(config: MqttConfig, subscriptions: List<String>, will: BusMessage) {
        synchronized(lock) {
            lastStart = Triple(config, subscriptions.toList(), will)
            session?.let { teardown(it, farewell = null) }
            session = null
            val client = try {
                MqttAsyncClient("tcp://${config.host}:${config.port}", clientId, MemoryPersistence())
            } catch (e: Exception) {
                return // URI ou identifiant invalide : le bus reste déconnecté.
            }
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                keepAliveInterval = KEEP_ALIVE_SECONDS
                connectionTimeout = CONNECTION_TIMEOUT_SECONDS
                isAutomaticReconnect = true
                setWill(will.topic, will.payload.toByteArray(Charsets.UTF_8), QOS, will.retained)
                if (config.username.isNotEmpty()) {
                    userName = config.username
                    password = config.password.toCharArray()
                }
            }
            val newSession = Session(client, options, subscriptions.toList())
            client.setCallback(newSession)
            session = newSession
            newSession.connect()
        }
    }

    override fun publish(message: BusMessage) {
        val current = session ?: return
        if (!_connected.value) return
        try {
            current.client.publish(
                message.topic,
                message.payload.toByteArray(Charsets.UTF_8),
                QOS,
                message.retained,
            )
        } catch (e: Exception) {
            // Connexion perdue entre-temps : le message est abandonné.
        }
    }

    override fun stop(farewell: BusMessage?) {
        synchronized(lock) {
            lastStart = null
            val current = session ?: return
            session = null
            teardown(current, farewell)
        }
    }

    /**
     * Après une mise en veille, la socket est morte mais Paho ne le sait qu'au keepalive suivant
     * (jusqu'à 1,5 × [KEEP_ALIVE_SECONDS]) : on repart d'une session neuve, hors du fil appelant
     * car la fermeture de l'ancienne peut bloquer quelques secondes.
     */
    override fun reconnectNow() {
        scheduler.execute {
            synchronized(lock) {
                val (config, subscriptions, will) = lastStart ?: return@execute
                start(config, subscriptions, will)
            }
        }
    }

    /** Ferme [s] ; appelé sous [lock]. Ne lève jamais d'exception. */
    private fun teardown(s: Session, farewell: BusMessage?) {
        s.deactivate()
        val client = s.client
        try {
            if (farewell != null && client.isConnected) {
                client.publish(
                    farewell.topic,
                    farewell.payload.toByteArray(Charsets.UTF_8),
                    QOS,
                    farewell.retained,
                ).waitForCompletion(FAREWELL_TIMEOUT_MILLIS)
            }
        } catch (e: Exception) {
            // Adieu non confirmé : on se déconnecte quand même.
        }
        try {
            if (client.isConnected) {
                client.disconnect(QUIESCE_MILLIS).waitForCompletion(QUIESCE_MILLIS + 1_000)
            }
        } catch (e: Exception) {
            // Déconnexion forcée ci-dessous.
        }
        try {
            if (client.isConnected) client.disconnectForcibly(0, FORCED_DISCONNECT_TIMEOUT_MILLIS, false)
        } catch (e: Exception) {
        }
        try {
            client.close(true)
        } catch (e: Exception) {
        }
        _connected.value = false
    }

    private inner class Session(
        val client: MqttAsyncClient,
        val options: MqttConnectOptions,
        val subscriptions: List<String>,
    ) : MqttCallbackExtended {

        @Volatile
        var active = true

        private var retry: ScheduledFuture<*>? = null

        private val isCurrent: Boolean get() = active && session === this

        fun connect() {
            if (!isCurrent) return
            try {
                client.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        scheduleRetry()
                    }
                })
            } catch (e: Exception) {
                scheduleRetry()
            }
        }

        @Synchronized
        private fun scheduleRetry() {
            if (!isCurrent) return
            retry = scheduler.schedule({ connect() }, retryDelayMillis, TimeUnit.MILLISECONDS)
        }

        /** Après cet appel, plus aucun rappel de cette session n'a d'effet. */
        @Synchronized
        fun deactivate() {
            active = false
            retry?.cancel(false)
            retry = null
        }

        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            if (!isCurrent) return
            if (subscriptions.isEmpty()) {
                markConnected()
                return
            }
            try {
                client.subscribe(
                    subscriptions.toTypedArray(),
                    IntArray(subscriptions.size) { QOS },
                    null,
                    object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) = markConnected()
                        // La connexion reste utilisable pour publier même si l'abonnement échoue.
                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) =
                            markConnected()
                    },
                )
            } catch (e: Exception) {
                markConnected()
            }
        }

        @Synchronized
        private fun markConnected() {
            if (isCurrent && client.isConnected) _connected.value = true
        }

        override fun connectionLost(cause: Throwable?) {
            if (isCurrent) _connected.value = false
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            if (!isCurrent) return
            _messages.tryEmit(BusMessage(topic, String(message.payload, Charsets.UTF_8), message.isRetained))
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    private companion object {
        const val QOS = 1
        const val KEEP_ALIVE_SECONDS = 15
        const val CONNECTION_TIMEOUT_SECONDS = 10
        const val DEFAULT_RETRY_DELAY_MILLIS = 5_000L
        const val FAREWELL_TIMEOUT_MILLIS = 1_000L
        const val QUIESCE_MILLIS = 1_000L
        const val FORCED_DISCONNECT_TIMEOUT_MILLIS = 500L

        /** Planificateur partagé des nouvelles tentatives de connexion initiale. */
        val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mqtt-retry").apply { isDaemon = true }
        }
    }
}
