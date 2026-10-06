package be.cameratv.controller

import be.cameratv.model.AppModel
import be.cameratv.model.AppState
import be.cameratv.model.Camera
import be.cameratv.model.MqttConfig
import be.cameratv.model.NvrConfig
import be.cameratv.model.Screen
import be.cameratv.model.bus.BusMessage
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalControllerTest {

    private val mqtt = MqttConfig(host = "broker.local")
    private val cams = listOf(Camera(1, "Porte"), Camera(2, "OUESTPTZ", ptz = true))

    private class Setup(val model: AppModel, val app: AppController, val bus: FakeBus, val external: ExternalController)

    /** Application sur la grille ; le contrôleur externe vit dans le backgroundScope (collecteurs sans fin). */
    private fun TestScope.setup(mqttConfig: MqttConfig? = mqtt): Setup {
        val model = AppModel(
            AppState(
                screen = Screen.Grid,
                config = NvrConfig(host = "nvr.local", username = "admin", password = "secret"),
                cameras = cams,
                mqtt = mqttConfig,
            ),
        )
        val app = AppController(model, FakeSettings(), FakeDriverFactory { cams }, this)
        val bus = FakeBus()
        val external = ExternalController(model, app, bus, backgroundScope)
        return Setup(model, app, bus, external)
    }

    private fun online(base: String, value: Boolean) = BusMessage("$base/online", value.toString(), retained = true)

    private val gridState =
        """{"screen":"grid","camera":null,"cameraName":null,"ptzMode":false,""" +
            """"cameras":[{"channel":1,"name":"Porte","ptz":false},{"channel":2,"name":"OUESTPTZ","ptz":true}]}"""

    @Test
    fun `sans configuration MQTT le bus n'est pas démarré`() = runTest {
        val s = setup(mqttConfig = null)
        s.external.start()
        runCurrent()
        assertTrue(s.bus.started.isEmpty())
        assertTrue(s.bus.stopped.isEmpty())
        assertFalse(s.model.state.value.mqttConnected)
    }

    @Test
    fun `start démarre le bus avec l'abonnement aux commandes et le testament`() = runTest {
        val s = setup()
        s.external.start()
        s.external.start() // Idempotent.
        runCurrent()
        assertEquals(
            listOf(FakeBus.Started(mqtt, listOf("cameratv/cmd/#"), online("cameratv", false))),
            s.bus.started,
        )
    }

    @Test
    fun `à la connexion, présence et état sont publiés et conservés`() = runTest {
        val s = setup()
        s.external.start()
        runCurrent()
        assertTrue(s.bus.published.isEmpty())
        s.bus.connect()
        runCurrent()
        assertTrue(s.model.state.value.mqttConnected)
        assertEquals(
            listOf(online("cameratv", true), BusMessage("cameratv/state", gridState, retained = true)),
            s.bus.published,
        )
    }

    @Test
    fun `chaque reconnexion republie la présence et l'état`() = runTest {
        val s = setup()
        s.external.start()
        s.bus.connect()
        runCurrent()
        s.bus.disconnect()
        runCurrent()
        assertFalse(s.model.state.value.mqttConnected)
        s.bus.connect()
        runCurrent()
        assertEquals(4, s.bus.published.size)
        assertEquals(online("cameratv", true), s.bus.published[2])
        assertEquals("cameratv/state", s.bus.published[3].topic)
    }

    @Test
    fun `un changement d'écran publie le nouvel état`() = runTest {
        val s = setup()
        s.external.start()
        s.bus.connect()
        runCurrent()
        s.bus.published.clear()
        s.app.onCommand(RemoteCommand.Digit(2))
        s.app.onCommand(RemoteCommand.Ok)
        runCurrent()
        // Les états intermédiaires peuvent être fusionnés : seul le dernier compte.
        assertTrue(s.bus.published.all { it.topic == "cameratv/state" && it.retained })
        assertEquals(
            BusMessage(
                "cameratv/state",
                """{"screen":"fullscreen","camera":2,"cameraName":"OUESTPTZ","ptzMode":true,""" +
                    """"cameras":[{"channel":1,"name":"Porte","ptz":false},{"channel":2,"name":"OUESTPTZ","ptz":true}]}""",
                retained = true,
            ),
            s.bus.published.last(),
        )
        s.bus.published.clear()
        s.model.update { it.copy(exitRequested = true) } // Hors projection : rien à publier.
        runCurrent()
        assertTrue(s.bus.published.isEmpty())
    }

    @Test
    fun `l'état n'est pas publié hors connexion`() = runTest {
        val s = setup()
        s.external.start()
        runCurrent()
        s.app.onCommand(RemoteCommand.Digit(1))
        runCurrent()
        assertTrue(s.bus.published.isEmpty())
    }

    @Test
    fun `les commandes reçues sont transmises au contrôleur`() = runTest {
        val s = setup()
        s.external.start()
        s.bus.connect()
        runCurrent()
        s.bus.receive("cameratv/cmd/show", """{"camera": "ouestptz"}""")
        runCurrent()
        assertEquals(Screen.Fullscreen(2), s.model.state.value.screen)
        s.bus.receive("cameratv/cmd/grid", "")
        runCurrent()
        assertEquals(Screen.Grid, s.model.state.value.screen)
        s.bus.receive("cameratv/cmd/exit", "")
        runCurrent()
        assertTrue(s.model.state.value.exitRequested)
    }

    @Test
    fun `les topics étrangers et les commandes invalides sont ignorés`() = runTest {
        val s = setup()
        s.external.start()
        s.bus.connect()
        runCurrent()
        s.bus.receive("autre/cmd/show", "1")
        s.bus.receive("cameratv/show", "1")
        s.bus.receive("cameratv/cmd/show/1", "1")
        s.bus.receive("cameratv/cmd/", "1")
        s.bus.receive("cameratv/state", "1")
        s.bus.receive("cameratv/cmd/show", "{pas du json")
        s.bus.receive("cameratv/cmd/show", "9")
        runCurrent()
        assertEquals(Screen.Grid, s.model.state.value.screen)
    }

    @Test
    fun `une nouvelle configuration change de broker et de topics`() = runTest {
        val s = setup()
        s.external.start()
        s.bus.connect()
        runCurrent()
        s.bus.published.clear()
        val other = MqttConfig(host = "autre.local", baseTopic = "maison/tv")
        s.model.update { it.copy(mqtt = other) }
        runCurrent()
        assertEquals(listOf(online("cameratv", false)), s.bus.published) // Adieu sur l'ancien préfixe.
        assertEquals(listOf<BusMessage?>(online("cameratv", false)), s.bus.stopped)
        assertEquals(FakeBus.Started(other, listOf("maison/tv/cmd/#"), online("maison/tv", false)), s.bus.started.last())
        assertFalse(s.model.state.value.mqttConnected)

        s.bus.connect()
        runCurrent()
        assertEquals(online("maison/tv", true), s.bus.published[1])
        assertEquals("maison/tv/state", s.bus.published[2].topic)
        s.bus.receive("cameratv/cmd/show", "1")
        runCurrent()
        assertEquals(Screen.Grid, s.model.state.value.screen)
        s.bus.receive("maison/tv/cmd/show", "1")
        runCurrent()
        assertEquals(Screen.Fullscreen(1), s.model.state.value.screen)
    }

    @Test
    fun `désactiver le pilotage arrête le bus`() = runTest {
        val s = setup()
        s.external.start()
        s.bus.connect()
        runCurrent()
        s.app.updateMqtt(null)
        runCurrent()
        assertEquals(listOf<BusMessage?>(online("cameratv", false)), s.bus.stopped)
        assertEquals(online("cameratv", false), s.bus.published.last())
        assertFalse(s.model.state.value.mqttConnected)
        assertEquals(1, s.bus.started.size)
    }

    @Test
    fun `stop publie le hors ligne, se déconnecte puis start reprend`() = runTest {
        val s = setup()
        s.external.start()
        s.bus.connect()
        runCurrent()
        s.external.stop()
        runCurrent()
        assertEquals(listOf<BusMessage?>(online("cameratv", false)), s.bus.stopped)
        assertEquals(online("cameratv", false), s.bus.published.last())
        assertFalse(s.model.state.value.mqttConnected)

        // Plus aucune commande n'est relayée une fois arrêté.
        s.bus.receive("cameratv/cmd/show", "1")
        runCurrent()
        assertEquals(Screen.Grid, s.model.state.value.screen)

        s.external.start()
        runCurrent()
        assertEquals(2, s.bus.started.size)
        s.bus.connect()
        s.bus.receive("cameratv/cmd/show", "1")
        runCurrent()
        assertEquals(Screen.Fullscreen(1), s.model.state.value.screen)
    }

    @Test
    fun `stop sans configuration ne touche pas au bus`() = runTest {
        val s = setup(mqttConfig = null)
        s.external.start()
        runCurrent()
        s.external.stop()
        assertTrue(s.bus.stopped.isEmpty())
    }
}
