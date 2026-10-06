package be.cameratv.controller

import be.cameratv.controller.CameraRef.ByChannel
import be.cameratv.controller.CameraRef.ByName
import be.cameratv.controller.ExternalCommand.Exit
import be.cameratv.controller.ExternalCommand.Ptz
import be.cameratv.controller.ExternalCommand.ShowCamera
import be.cameratv.controller.ExternalCommand.ShowGrid
import be.cameratv.model.AppModel
import be.cameratv.model.Camera
import be.cameratv.model.MqttConfig
import be.cameratv.model.NvrConfig
import be.cameratv.model.PtzDirection
import be.cameratv.model.Screen
import be.cameratv.model.SettingsRepository
import be.cameratv.model.driver.CameraDriverException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppControllerExternalTest {

    private val config = NvrConfig(host = "nvr.local", username = "admin", password = "secret")
    private val mqtt = MqttConfig(host = "broker.local")

    private val cams = listOf(
        Camera(1, "Porte"),
        Camera(2, "Jardin"),
        Camera(3, "OUESTPTZ", ptz = true),
        Camera(4, "Dôme", ptz = true),
    )

    private fun TestScope.controller(
        settings: FakeSettings = FakeSettings(stored = config),
        factory: FakeDriverFactory = FakeDriverFactory { cams },
    ) = AppController(AppModel(), settings, factory, this)

    /** Connecté au NVR factice, sur la grille. */
    private fun TestScope.connected(factory: FakeDriverFactory = FakeDriverFactory { cams }): AppController {
        val c = controller(factory = factory)
        c.start()
        advanceUntilIdle()
        assertEquals(Screen.Grid, c.state.value.screen)
        return c
    }

    // --- Configuration MQTT --------------------------------------------------------------------

    @Test
    fun `start charge la configuration MQTT même sans configuration du NVR`() = runTest {
        val settings = FakeSettings().apply { storedMqtt = mqtt }
        val c = controller(settings = settings)
        c.start()
        advanceUntilIdle()
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals(mqtt, c.state.value.mqtt)
    }

    @Test
    fun `start charge la configuration MQTT avec le NVR`() = runTest {
        val settings = FakeSettings(stored = config).apply { storedMqtt = mqtt }
        val c = controller(settings = settings)
        c.start()
        advanceUntilIdle()
        assertEquals(Screen.Grid, c.state.value.screen)
        assertEquals(mqtt, c.state.value.mqtt)
    }

    @Test
    fun `une configuration MQTT illisible désactive le pilotage sans bloquer le démarrage`() = runTest {
        val settings = FakeSettings(stored = config).apply { mqttFails = true }
        val c = controller(settings = settings)
        c.start()
        advanceUntilIdle()
        assertEquals(Screen.Grid, c.state.value.screen)
        assertNull(c.state.value.mqtt)
    }

    @Test
    fun `updateMqtt enregistre et met à jour l'état`() = runTest {
        val settings = FakeSettings(stored = config)
        val c = controller(settings = settings)
        c.updateMqtt(mqtt)
        assertEquals(mqtt, c.state.value.mqtt)
        advanceUntilIdle()
        assertEquals(mqtt, settings.storedMqtt)
        c.updateMqtt(null)
        advanceUntilIdle()
        assertNull(c.state.value.mqtt)
        assertNull(settings.storedMqtt)
    }

    @Test
    fun `updateMqtt ignore un échec d'enregistrement`() = runTest {
        val settings = FakeSettings(stored = config).apply { mqttFails = true }
        val c = controller(settings = settings)
        c.updateMqtt(mqtt)
        advanceUntilIdle()
        assertEquals(mqtt, c.state.value.mqtt)
    }

    @Test
    fun `un chargement lent ne remplace pas une configuration MQTT saisie entre-temps`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val settings = object : SettingsRepository by FakeSettings(stored = config) {
            override suspend fun loadMqtt(): MqttConfig? {
                gate.await()
                return MqttConfig(host = "ancien.local")
            }
        }
        val c = AppController(AppModel(), settings, FakeDriverFactory { cams }, this)
        c.start()
        runCurrent()
        c.updateMqtt(mqtt)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(mqtt, c.state.value.mqtt)
    }

    // --- Écrans sans caméras -------------------------------------------------------------------

    @Test
    fun `les commandes sont ignorées sur l'écran de configuration`() = runTest {
        val c = controller(settings = FakeSettings())
        c.start()
        advanceUntilIdle()
        assertEquals(Screen.Setup, c.state.value.screen)
        assertFalse(c.onExternalCommand(ShowCamera(ByChannel(1))))
        assertFalse(c.onExternalCommand(ShowGrid))
        assertFalse(c.onExternalCommand(Exit))
        assertEquals(Screen.Setup, c.state.value.screen)
        assertFalse(c.state.value.exitRequested)
    }

    @Test
    fun `pendant la connexion, la dernière commande est appliquée dès la réussite`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = controller(factory = FakeDriverFactory { gate.await(); cams })
        c.start()
        runCurrent()
        assertEquals(Screen.Loading, c.state.value.screen)
        assertTrue(c.onExternalCommand(ShowCamera(ByChannel(1))))
        assertTrue(c.onExternalCommand(ShowCamera(ByName("ouestptz"))))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Screen.Fullscreen(3), c.state.value.screen)
        assertEquals(2, c.state.value.focusedIndex)
    }

    @Test
    fun `une commande en attente est abandonnée si la connexion échoue`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fail = true
        val c = controller(factory = FakeDriverFactory {
            gate.await()
            if (fail) throw CameraDriverException("NVR injoignable") else cams
        })
        c.start()
        runCurrent()
        assertTrue(c.onExternalCommand(ShowCamera(ByChannel(2))))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Screen.Setup, c.state.value.screen)
        fail = false
        c.retry()
        advanceUntilIdle()
        assertEquals(Screen.Grid, c.state.value.screen)
    }

    @Test
    fun `une commande Exit en attente est appliquée après la connexion`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = controller(factory = FakeDriverFactory { gate.await(); cams })
        c.start()
        c.onUiVisibilityChanged(true)
        runCurrent()
        assertTrue(c.onExternalCommand(Exit))
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(c.state.value.exitRequested)
    }

    // --- Affichage -----------------------------------------------------------------------------

    @Test
    fun `ShowCamera par numéro ou par nom passe en plein écran et aligne le focus`() = runTest {
        val c = connected()
        assertTrue(c.onExternalCommand(ShowCamera(ByChannel(2))))
        assertEquals(Screen.Fullscreen(2), c.state.value.screen)
        assertEquals(1, c.state.value.focusedIndex)
        assertTrue(c.onExternalCommand(ShowCamera(ByName("dÔme"))))
        assertEquals(Screen.Fullscreen(4), c.state.value.screen)
        assertEquals(3, c.state.value.focusedIndex)
    }

    @Test
    fun `ShowCamera sur une caméra inconnue est refusé`() = runTest {
        val c = connected()
        assertFalse(c.onExternalCommand(ShowCamera(ByChannel(9))))
        assertFalse(c.onExternalCommand(ShowCamera(ByName("Garage"))))
        assertEquals(Screen.Grid, c.state.value.screen)
    }

    @Test
    fun `ShowCamera quitte le mode PTZ et arrête le mouvement de la télécommande`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        c.onExternalCommand(ShowCamera(ByChannel(3)))
        c.onCommand(RemoteCommand.Ok)
        assertTrue(c.state.value.ptzMode)
        c.onCommand(RemoteCommand.Left)
        assertTrue(c.onExternalCommand(ShowCamera(ByChannel(1))))
        advanceUntilIdle()
        assertEquals(Screen.Fullscreen(1), c.state.value.screen)
        assertFalse(c.state.value.ptzMode)
        assertEquals(listOf("start:3:LEFT", "stop:3:LEFT"), factory.ptzCalls)
    }

    @Test
    fun `ShowGrid revient à la grille en gardant le focus`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        c.onExternalCommand(ShowCamera(ByChannel(3)))
        c.onCommand(RemoteCommand.Ok)
        c.onCommand(RemoteCommand.Up)
        assertTrue(c.onExternalCommand(ShowGrid))
        advanceUntilIdle()
        assertEquals(Screen.Grid, c.state.value.screen)
        assertEquals(2, c.state.value.focusedIndex)
        assertFalse(c.state.value.ptzMode)
        assertEquals(listOf("start:3:UP", "stop:3:UP"), factory.ptzCalls)
    }

    // --- Retour automatique --------------------------------------------------------------------

    @Test
    fun `après la durée, retour à la grille avec son focus`() = runTest {
        val c = connected()
        c.onCommand(RemoteCommand.Right) // Focus sur la deuxième tuile.
        assertTrue(c.onExternalCommand(ShowCamera(ByChannel(4), durationSec = 30)))
        assertEquals(Screen.Fullscreen(4), c.state.value.screen)
        advanceTimeBy(29_999)
        assertEquals(Screen.Fullscreen(4), c.state.value.screen)
        advanceTimeBy(2)
        assertEquals(Screen.Grid, c.state.value.screen)
        assertEquals(1, c.state.value.focusedIndex)
    }

    @Test
    fun `après la durée, retour à la caméra affichée avant`() = runTest {
        val c = connected()
        c.onCommand(RemoteCommand.Digit(2))
        assertEquals(Screen.Fullscreen(2), c.state.value.screen)
        c.onExternalCommand(ShowCamera(ByName("Porte"), durationSec = 10))
        assertEquals(Screen.Fullscreen(1), c.state.value.screen)
        advanceTimeBy(10_001)
        assertEquals(Screen.Fullscreen(2), c.state.value.screen)
        assertEquals(1, c.state.value.focusedIndex)
    }

    @Test
    fun `une touche de la télécommande annule le retour automatique`() = runTest {
        val c = connected()
        c.onExternalCommand(ShowCamera(ByChannel(3), durationSec = 10))
        advanceTimeBy(5_000)
        assertTrue(c.onCommand(RemoteCommand.Right))
        assertEquals(Screen.Fullscreen(4), c.state.value.screen)
        advanceTimeBy(60_000)
        assertEquals(Screen.Fullscreen(4), c.state.value.screen)
    }

    @Test
    fun `une touche non consommée n'annule pas le retour automatique`() = runTest {
        val c = connected()
        c.onExternalCommand(ShowCamera(ByChannel(3), durationSec = 10))
        c.onCommandReleased(RemoteCommand.Left)
        advanceTimeBy(10_001)
        assertEquals(Screen.Grid, c.state.value.screen)
    }

    @Test
    fun `un nouvel affichage temporaire garde l'écran d'origine et relance le minuteur`() = runTest {
        val c = connected()
        c.onExternalCommand(ShowCamera(ByChannel(3), durationSec = 10))
        advanceTimeBy(8_000)
        c.onExternalCommand(ShowCamera(ByChannel(4), durationSec = 10))
        advanceTimeBy(8_000)
        assertEquals(Screen.Fullscreen(4), c.state.value.screen)
        advanceTimeBy(2_001)
        assertEquals(Screen.Grid, c.state.value.screen)
        assertEquals(0, c.state.value.focusedIndex)
    }

    @Test
    fun `ShowGrid et Exit annulent le retour automatique`() = runTest {
        val c = connected()
        c.onExternalCommand(ShowCamera(ByChannel(3), durationSec = 10))
        c.onExternalCommand(ShowGrid)
        c.onCommand(RemoteCommand.Digit(2)) // Avant l'échéance : rien ne doit la suivre.
        advanceTimeBy(20_000)
        assertEquals(Screen.Fullscreen(2), c.state.value.screen)

        val c2 = connected()
        c2.onExternalCommand(ShowCamera(ByChannel(3), durationSec = 10))
        c2.onExternalCommand(Exit)
        advanceTimeBy(20_000)
        assertEquals(Screen.Fullscreen(3), c2.state.value.screen)
    }

    @Test
    fun `ShowCamera sans durée rend l'affichage définitif`() = runTest {
        val c = connected()
        c.onExternalCommand(ShowCamera(ByChannel(3), durationSec = 10))
        c.onExternalCommand(ShowCamera(ByChannel(1)))
        advanceTimeBy(20_000)
        assertEquals(Screen.Fullscreen(1), c.state.value.screen)
    }

    @Test
    fun `afficher temporairement la caméra déjà affichée ne programme aucun retour`() = runTest {
        val c = connected()
        c.onCommand(RemoteCommand.Digit(2))
        c.onExternalCommand(ShowCamera(ByChannel(2), durationSec = 5))
        advanceTimeBy(10_000)
        assertEquals(Screen.Fullscreen(2), c.state.value.screen)
    }

    // --- Exit ----------------------------------------------------------------------------------

    @Test
    fun `Exit arrête le mouvement, quitte le mode PTZ et lève le drapeau jusqu'à l'acquittement`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        c.onUiVisibilityChanged(true)
        c.onExternalCommand(ShowCamera(ByChannel(4)))
        c.onCommand(RemoteCommand.Ok)
        c.onCommand(RemoteCommand.ChannelUp)
        assertTrue(c.onExternalCommand(Exit))
        advanceUntilIdle()
        assertTrue(c.state.value.exitRequested)
        assertFalse(c.state.value.ptzMode)
        assertEquals(listOf("start:4:ZOOM_IN", "stop:4:ZOOM_IN"), factory.ptzCalls)
        c.onExitHandled()
        assertFalse(c.state.value.exitRequested)
    }

    // --- PTZ -----------------------------------------------------------------------------------

    @Test
    fun `un déplacement démarre puis s'arrête après la durée, sans changer d'écran`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        assertTrue(c.onExternalCommand(Ptz(ByName("ouestptz"), PtzAction.Move(PtzDirection.LEFT, 1_000))))
        runCurrent()
        assertEquals(listOf("start:3:LEFT"), factory.ptzCalls)
        advanceTimeBy(999)
        assertEquals(listOf("start:3:LEFT"), factory.ptzCalls)
        advanceTimeBy(2)
        assertEquals(listOf("start:3:LEFT", "stop:3:LEFT"), factory.ptzCalls)
        assertEquals(Screen.Grid, c.state.value.screen)
    }

    @Test
    fun `un pilote lent ne laisse jamais l'arrêt d'un déplacement externe doubler son démarrage`() = runTest {
        val factory = FakeDriverFactory { cams }
        factory.onPtzStart = { _, _ -> delay(800) }
        val c = connected(factory)
        c.onExternalCommand(Ptz(ByChannel(3), PtzAction.Move(PtzDirection.UP, 100)))
        c.onExternalCommand(Ptz(ByChannel(3), PtzAction.Preset(2)))
        advanceUntilIdle()
        assertEquals(listOf("start:3:UP", "stop:3:UP", "preset:3:2"), factory.ptzCalls)
    }

    @Test
    fun `un déplacement externe arrête d'abord le mouvement de la télécommande sur cette caméra`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        c.onExternalCommand(ShowCamera(ByChannel(3)))
        c.onCommand(RemoteCommand.Ok)
        c.onCommand(RemoteCommand.Left)
        c.onExternalCommand(Ptz(ByChannel(3), PtzAction.Move(PtzDirection.UP, 500)))
        advanceUntilIdle()
        assertEquals(listOf("start:3:LEFT", "stop:3:LEFT", "start:3:UP", "stop:3:UP"), factory.ptzCalls)
        assertEquals(Screen.Fullscreen(3), c.state.value.screen)
        assertTrue(c.state.value.ptzMode)
    }

    @Test
    fun `un déplacement externe sur une autre caméra laisse la télécommande piloter la sienne`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        c.onExternalCommand(ShowCamera(ByChannel(3)))
        c.onCommand(RemoteCommand.Ok)
        c.onCommand(RemoteCommand.Left)
        c.onExternalCommand(Ptz(ByChannel(4), PtzAction.Move(PtzDirection.RIGHT, 300)))
        advanceTimeBy(400)
        assertEquals(listOf("start:3:LEFT", "start:4:RIGHT", "stop:4:RIGHT"), factory.ptzCalls)
        advanceUntilIdle() // Minuteur de sécurité de la télécommande.
        assertEquals(listOf("start:3:LEFT", "start:4:RIGHT", "stop:4:RIGHT", "stop:3:LEFT"), factory.ptzCalls)
    }

    @Test
    fun `un nouveau déplacement remplace le précédent sur la même caméra`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        c.onExternalCommand(Ptz(ByChannel(4), PtzAction.Move(PtzDirection.LEFT, 5_000)))
        advanceTimeBy(100)
        c.onExternalCommand(Ptz(ByChannel(4), PtzAction.Move(PtzDirection.RIGHT, 500)))
        advanceUntilIdle()
        assertEquals(listOf("start:4:LEFT", "stop:4:LEFT", "start:4:RIGHT", "stop:4:RIGHT"), factory.ptzCalls)
    }

    @Test
    fun `la télécommande reprend la main sur un déplacement externe`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        c.onExternalCommand(Ptz(ByChannel(3), PtzAction.Move(PtzDirection.LEFT, 5_000)))
        c.onExternalCommand(ShowCamera(ByChannel(3)))
        c.onCommand(RemoteCommand.Ok)
        c.onCommand(RemoteCommand.Down)
        c.onCommandReleased(RemoteCommand.Down)
        advanceUntilIdle()
        assertEquals(listOf("start:3:LEFT", "stop:3:LEFT", "start:3:DOWN", "stop:3:DOWN"), factory.ptzCalls)
    }

    @Test
    fun `préréglage et arrêt sur une caméra motorisée`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        assertTrue(c.onExternalCommand(Ptz(ByChannel(4), PtzAction.Preset(2))))
        assertTrue(c.onExternalCommand(Ptz(ByChannel(4), PtzAction.Stop))) // Immobile : rien à envoyer.
        c.onExternalCommand(Ptz(ByChannel(3), PtzAction.Move(PtzDirection.ZOOM_OUT, 5_000)))
        advanceTimeBy(1_000)
        assertTrue(c.onExternalCommand(Ptz(ByChannel(3), PtzAction.Stop)))
        advanceUntilIdle()
        assertEquals(listOf("preset:4:2", "start:3:ZOOM_OUT", "stop:3:ZOOM_OUT"), factory.ptzCalls)
        assertEquals(Screen.Grid, c.state.value.screen)
    }

    @Test
    fun `PTZ refusé sur une caméra fixe ou inconnue`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        assertFalse(c.onExternalCommand(Ptz(ByChannel(1), PtzAction.Move(PtzDirection.LEFT, 500))))
        assertFalse(c.onExternalCommand(Ptz(ByName("Jardin"), PtzAction.Preset(1))))
        assertFalse(c.onExternalCommand(Ptz(ByChannel(7), PtzAction.Stop)))
        advanceUntilIdle()
        assertTrue(factory.ptzCalls.isEmpty())
    }

    @Test
    fun `une reconnexion arrête les déplacements externes avec l'ancien pilote`() = runTest {
        val factory = FakeDriverFactory { cams }
        val c = connected(factory)
        c.onExternalCommand(Ptz(ByChannel(3), PtzAction.Move(PtzDirection.LEFT, 5_000)))
        runCurrent()
        c.retry()
        advanceUntilIdle()
        assertEquals(listOf("start:3:LEFT", "stop:3:LEFT"), factory.ptzCalls)
    }

    // --- Application en arrière-plan ----------------------------------------------------------

    @Test
    fun `une caméra demandée pendant une autre application ramène l'écran au premier plan`() = runTest {
        val c = connected(FakeDriverFactory { cams })
        assertFalse(c.state.value.uiVisible)
        c.onExternalCommand(ShowCamera(ByChannel(4)))
        assertTrue(c.state.value.foregroundRequested)
        c.onUiVisibilityChanged(true)
        assertFalse(c.state.value.foregroundRequested)
    }

    @Test
    fun `l'écran visible ne demande pas de passage au premier plan`() = runTest {
        val c = connected(FakeDriverFactory { cams })
        c.onUiVisibilityChanged(true)
        c.onExternalCommand(ShowGrid)
        assertFalse(c.state.value.foregroundRequested)
    }

    @Test
    fun `fin d'affichage temporaire ouvert depuis l'arrière-plan, retour à l'application précédente`() = runTest {
        val c = connected(FakeDriverFactory { cams })
        c.onExternalCommand(ShowCamera(ByChannel(4), durationSec = 30))
        c.onUiVisibilityChanged(true)
        advanceTimeBy(30_001)
        assertTrue(c.state.value.exitRequested)
    }

    @Test
    fun `fin d'affichage temporaire ouvert depuis l'écran visible, pas de sortie`() = runTest {
        val c = connected(FakeDriverFactory { cams })
        c.onUiVisibilityChanged(true)
        c.onExternalCommand(ShowCamera(ByChannel(4), durationSec = 30))
        advanceTimeBy(30_001)
        assertFalse(c.state.value.exitRequested)
        assertEquals(Screen.Grid, c.state.value.screen)
    }

    @Test
    fun `Exit pendant une autre application ne fait rien`() = runTest {
        val c = connected(FakeDriverFactory { cams })
        assertTrue(c.onExternalCommand(Exit))
        assertFalse(c.state.value.exitRequested)
    }
}
