package be.cameratv.model.bus

import be.cameratv.model.MqttConfig
import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Tests contre un broker Moquette embarqué (en mémoire, sur un port libre de localhost). */
class PahoMessageBusTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val brokerPort = freePort()
    private var broker: Server? = null
    private val bus = PahoMessageBus("cameratv-test-bus", retryDelayMillis = 200)
    private val observers = mutableListOf<MqttClient>()
    private var proxy: TcpProxy? = null

    private val will = BusMessage("cameratv/online", "offline-will", retained = true)

    @After
    fun tearDown() {
        bus.stop()
        observers.forEach { runCatching { it.disconnectForcibly(0, 500); it.close() } }
        proxy?.close()
        broker?.stopServer()
    }

    @Test
    fun `connexion, abonnement et reception`() {
        startBroker()
        bus.start(config(), listOf("cameratv/cmd/#"), will)
        awaitConnected()

        val received = LinkedBlockingQueue<BusMessage>()
        val collector = thread { runBlocking { bus.messages.collect { received.put(it) } } }
        try {
            Thread.sleep(100) // le collecteur doit être abonné avant la publication
            observer().publish("cameratv/cmd/camera", "3".toByteArray(), 1, false)
            assertEquals(BusMessage("cameratv/cmd/camera", "3", retained = false), received.poll(5, TimeUnit.SECONDS))
        } finally {
            collector.interrupt()
        }
    }

    @Test
    fun `message retenu recu a l'abonnement`() {
        startBroker()
        observer().publish("cameratv/cmd/mode", "grille".toByteArray(), 1, true)

        val received = LinkedBlockingQueue<BusMessage>()
        val collector = thread { runBlocking { bus.messages.collect { received.put(it) } } }
        try {
            Thread.sleep(100)
            bus.start(config(), listOf("cameratv/cmd/#"), will)
            // Moquette 0.17 ne positionne pas le drapeau RETAIN à la livraison : seul le contenu
            // est vérifié ici (le drapeau est recopié tel quel depuis Paho).
            val message = received.poll(5, TimeUnit.SECONDS)
            assertEquals("cameratv/cmd/mode", message?.topic)
            assertEquals("grille", message?.payload)
        } finally {
            collector.interrupt()
        }
    }

    @Test
    fun `publication retenue`() {
        startBroker()
        bus.start(config(), emptyList(), will)
        awaitConnected()
        bus.publish(BusMessage("cameratv/state", """{"camera":2}""", retained = true))

        Thread.sleep(200) // laisse au broker le temps d'enregistrer le message
        // Un abonné arrivé après la publication reçoit le message retenu.
        val (_, queue) = subscriber("cameratv/state")
        assertEquals("""{"camera":2}""", queue.poll(5, TimeUnit.SECONDS)?.payload)
    }

    @Test
    fun `publication ignoree sans connexion`() {
        bus.publish(BusMessage("cameratv/state", "x"))
        bus.start(config(port = freePort()), emptyList(), will)
        bus.publish(BusMessage("cameratv/state", "x"))
        assertFalse(bus.connected.value)
    }

    @Test
    fun `options de connexion`() {
        bus.start(config(port = freePort()).copy(username = "jeedom", password = "secret"), emptyList(), will)
        val options = bus.currentOptions!!
        assertTrue(options.isCleanSession)
        assertTrue(options.isAutomaticReconnect)
        assertEquals(15, options.keepAliveInterval)
        assertEquals(10, options.connectionTimeout)
        assertEquals("cameratv/online", options.willDestination)
        assertEquals("offline-will", String(options.willMessage.payload))
        assertEquals(1, options.willMessage.qos)
        assertTrue(options.willMessage.isRetained)
        assertEquals("jeedom", options.userName)
        assertEquals("secret", String(options.password))

        bus.start(config(port = freePort()), emptyList(), will)
        assertNull(bus.currentOptions!!.userName)
        assertNull(bus.currentOptions!!.password)
    }

    @Test
    fun `testament publie par le broker si la connexion est coupee sans stop`() {
        startBroker()
        val tcp = TcpProxy(brokerPort).also { proxy = it }
        val (_, queue) = subscriber("cameratv/online")
        bus.start(config(port = tcp.port), emptyList(), will)
        awaitConnected()
        bus.publish(BusMessage("cameratv/online", "online", retained = true))
        assertEquals("online", queue.poll(5, TimeUnit.SECONDS)?.payload)

        tcp.cutConnections()

        assertEquals("offline-will", queue.poll(5, TimeUnit.SECONDS)?.payload)
        // La reconnexion automatique de Paho rétablit ensuite la session.
        awaitConnected(timeoutMillis = 10_000)
    }

    @Test
    fun `nouvelles tentatives tant que le broker est absent`() {
        bus.start(config(), listOf("cameratv/cmd/#"), will)
        Thread.sleep(600)
        assertFalse(bus.connected.value)

        startBroker()
        awaitConnected()

        // Abonnement effectif après la connexion tardive.
        val received = LinkedBlockingQueue<BusMessage>()
        val collector = thread { runBlocking { bus.messages.collect { received.put(it) } } }
        try {
            Thread.sleep(100)
            observer().publish("cameratv/cmd/camera", "1".toByteArray(), 1, false)
            assertEquals("1", received.poll(5, TimeUnit.SECONDS)?.payload)
        } finally {
            collector.interrupt()
        }
    }

    @Test
    fun `arret avec message d'adieu sans testament`() {
        startBroker()
        val (_, queue) = subscriber("cameratv/online")
        bus.start(config(), emptyList(), will)
        awaitConnected()

        bus.stop(BusMessage("cameratv/online", "offline", retained = true))

        assertFalse(bus.connected.value)
        assertEquals("offline", queue.poll(5, TimeUnit.SECONDS)?.payload)
        // Déconnexion propre : le broker ne publie pas le testament.
        assertNull(queue.poll(500, TimeUnit.MILLISECONDS))
        bus.stop(BusMessage("cameratv/online", "again")) // idempotent
        assertNull(queue.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `arret pendant les tentatives de connexion`() {
        bus.start(config(), emptyList(), will)
        Thread.sleep(300)
        bus.stop(BusMessage("cameratv/online", "offline"))

        startBroker()
        Thread.sleep(800)
        assertFalse(bus.connected.value)
    }

    @Test
    fun `redemarrage remplace la session precedente`() {
        startBroker()
        val (_, queue) = subscriber("cameratv/online")
        bus.start(config(), emptyList(), will)
        awaitConnected()
        bus.start(config(), emptyList(), will)
        awaitConnected()
        bus.publish(BusMessage("cameratv/online", "online"))
        assertEquals("online", queue.poll(5, TimeUnit.SECONDS)?.payload)
    }

    // --- Outils ---

    @Test
    fun `reconnexion immédiate, nouvelle session et abonnements rétablis`() {
        startBroker()
        bus.start(config(), listOf("cameratv/cmd/#"), will)
        awaitConnected()

        bus.reconnectNow()
        // La session est remplacée : déconnexion puis reconnexion.
        runBlocking { withTimeout(5_000) { bus.connected.first { !it } } }
        awaitConnected()

        val received = LinkedBlockingQueue<BusMessage>()
        val collector = thread { runBlocking { bus.messages.collect { received.put(it) } } }
        try {
            Thread.sleep(100)
            observer().publish("cameratv/cmd/grid", "".toByteArray(), 1, false)
            assertEquals("cameratv/cmd/grid", received.poll(5, TimeUnit.SECONDS)?.topic)
        } finally {
            collector.interrupt()
        }
    }

    @Test
    fun `reconnexion immédiate sans effet si le bus est arrêté`() {
        startBroker()
        bus.reconnectNow()
        Thread.sleep(500)
        assertEquals(false, bus.connected.value)
    }

    private fun config(port: Int = brokerPort) = MqttConfig(host = "localhost", port = port)

    private fun startBroker() {
        val props = Properties().apply {
            setProperty("host", "127.0.0.1")
            setProperty("port", brokerPort.toString())
            setProperty("websocket_port", "disabled")
            setProperty("persistence_enabled", "false")
            setProperty("allow_anonymous", "true")
        }
        // Le port libre choisi au départ peut être brièvement occupé : quelques nouvelles tentatives.
        var attempt = 0
        while (true) {
            try {
                broker = Server().apply { startServer(MemoryConfig(props)) }
                return
            } catch (e: RuntimeException) {
                if (++attempt >= 5) throw e
                Thread.sleep(300)
            }
        }
    }

    private fun awaitConnected(timeoutMillis: Long = 5_000) = runBlocking {
        withTimeout(timeoutMillis) { bus.connected.first { it } }
    }

    private fun observer(): MqttClient =
        MqttClient("tcp://localhost:$brokerPort", "observer-${observers.size}", MemoryPersistence()).also {
            it.connect(MqttConnectOptions().apply { isCleanSession = true })
            observers += it
        }

    private fun subscriber(topic: String): Pair<MqttClient, LinkedBlockingQueue<BusMessage>> {
        val queue = LinkedBlockingQueue<BusMessage>()
        val client = MqttClient("tcp://localhost:$brokerPort", "observer-${observers.size}", MemoryPersistence())
        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) = Unit
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            override fun messageArrived(topic: String, message: MqttMessage) {
                queue.put(BusMessage(topic, String(message.payload), message.isRetained))
            }
        })
        client.connect(MqttConnectOptions().apply { isCleanSession = true })
        client.subscribe(topic, 1)
        observers += client
        return client to queue
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Relais TCP permettant de couper brutalement la connexion, comme une perte réseau. */
    private class TcpProxy(targetPort: Int) : AutoCloseable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        private val sockets = CopyOnWriteArrayList<Socket>()
        val port: Int = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    val upstream = runCatching { Socket("127.0.0.1", targetPort) }.getOrNull()
                    if (upstream == null) {
                        client.close()
                        continue
                    }
                    sockets += client
                    sockets += upstream
                    pump(client, upstream)
                    pump(upstream, client)
                }
            }
        }

        private fun pump(from: Socket, to: Socket) = thread(isDaemon = true) {
            runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
            runCatching { from.close() }
            runCatching { to.close() }
        }

        fun cutConnections() {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }

        override fun close() {
            runCatching { server.close() }
            cutConnections()
        }
    }
}
