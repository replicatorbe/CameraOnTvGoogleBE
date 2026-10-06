package be.cameratv.controller

import be.cameratv.controller.RemoteCommand.Back
import be.cameratv.controller.RemoteCommand.ChannelDown
import be.cameratv.controller.RemoteCommand.ChannelUp
import be.cameratv.controller.RemoteCommand.Digit
import be.cameratv.controller.RemoteCommand.Down
import be.cameratv.controller.RemoteCommand.Left
import be.cameratv.controller.RemoteCommand.Menu
import be.cameratv.controller.RemoteCommand.Ok
import be.cameratv.controller.RemoteCommand.Right
import be.cameratv.controller.RemoteCommand.Up
import be.cameratv.model.AppModel
import be.cameratv.model.AppState
import be.cameratv.model.Camera
import be.cameratv.model.NvrConfig
import be.cameratv.model.Screen
import be.cameratv.model.StreamQuality
import be.cameratv.model.driver.AuthenticationException
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

class AppControllerTest {

    private val config = NvrConfig(host = "192.168.1.10", username = "admin", password = "secret")

    private fun TestScope.controller(
        settings: FakeSettings = FakeSettings(),
        factory: FakeDriverFactory = FakeDriverFactory { cameras(4) },
        initial: AppState = AppState(),
    ) = AppController(AppModel(initial), settings, factory, this)

    /** Contrôleur déjà positionné sur un écran, sans passer par la connexion. */
    private fun TestScope.controllerOn(screen: Screen, cameraCount: Int, focusedIndex: Int = 0) =
        controller(initial = AppState(screen = screen, config = config, cameras = cameras(cameraCount), focusedIndex = focusedIndex))

    // --- Démarrage et connexion ----------------------------------------------------------------

    @Test
    fun `start sans configuration affiche Setup`() = runTest {
        val factory = FakeDriverFactory { cameras(4) }
        val c = controller(factory = factory)
        c.start()
        assertEquals(Screen.Loading, c.state.value.screen)
        advanceUntilIdle()
        assertEquals(Screen.Setup, c.state.value.screen)
        assertTrue(factory.created.isEmpty())
    }

    @Test
    fun `start avec configuration affiche la grille et enregistre la configuration`() = runTest {
        val settings = FakeSettings(stored = config)
        val c = controller(settings = settings, factory = FakeDriverFactory { cameras(8) })
        c.start()
        advanceUntilIdle()
        val state = c.state.value
        assertEquals(Screen.Grid, state.screen)
        assertEquals(cameras(8), state.cameras)
        assertEquals(config, state.config)
        assertNull(state.error)
        assertEquals(listOf(config), settings.saved)
        assertEquals("rtsp://192.168.1.10/ch2/SUB", c.streamUrl(state.cameras[1], StreamQuality.SUB))
    }

    @Test
    fun `streamUrl est null tant qu'aucun pilote n'est connecté`() = runTest {
        val c = controller()
        assertNull(c.streamUrl(Camera(1, "x"), StreamQuality.MAIN))
    }

    @Test
    fun `identifiants refusés renvoient vers Setup avec erreur et configuration conservée`() = runTest {
        val settings = FakeSettings()
        val c = controller(settings, FakeDriverFactory { throw AuthenticationException("401") })
        c.submitSetup(config)
        advanceUntilIdle()
        val state = c.state.value
        assertEquals(Screen.Setup, state.screen)
        assertEquals("Identifiants refusés par le NVR", state.error)
        assertEquals(config, state.config)
        assertTrue(settings.saved.isEmpty())
    }

    @Test
    fun `le message d'erreur du pilote est propagé`() = runTest {
        val c = controller(factory = FakeDriverFactory { throw CameraDriverException("NVR injoignable") })
        c.submitSetup(config)
        advanceUntilIdle()
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals("NVR injoignable", c.state.value.error)
        assertEquals(config, c.state.value.config)
    }

    @Test
    fun `une exception inattendue donne une erreur générique`() = runTest {
        val c = controller(factory = FakeDriverFactory { throw IllegalStateException("boom") })
        c.submitSetup(config)
        advanceUntilIdle()
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals("Connexion au NVR impossible", c.state.value.error)
    }

    @Test
    fun `aucune caméra renvoie vers Setup`() = runTest {
        val settings = FakeSettings()
        val c = controller(settings, FakeDriverFactory { emptyList() })
        c.submitSetup(config)
        advanceUntilIdle()
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals("Aucune caméra trouvée sur le NVR", c.state.value.error)
        assertTrue(settings.saved.isEmpty())
    }

    @Test
    fun `retry relance la connexion avec la configuration courante`() = runTest {
        var fail = true
        val factory = FakeDriverFactory {
            if (fail) throw CameraDriverException("timeout") else cameras(2)
        }
        val c = controller(factory = factory)
        c.submitSetup(config)
        advanceUntilIdle()
        assertEquals(Screen.Setup, c.state.value.screen)
        fail = false
        c.retry()
        advanceUntilIdle()
        assertEquals(Screen.Grid, c.state.value.screen)
        assertNull(c.state.value.error)
        assertEquals(listOf(config, config), factory.created)
    }

    @Test
    fun `le focus est ramené dans les bornes après connexion`() = runTest {
        val c = controller(
            factory = FakeDriverFactory { cameras(3) },
            initial = AppState(focusedIndex = 7),
        )
        c.submitSetup(config)
        advanceUntilIdle()
        assertEquals(2, c.state.value.focusedIndex)
    }

    @Test
    fun `un second submitSetup annule la connexion en cours`() = runTest {
        val first = config.copy(host = "10.0.0.1")
        val second = config.copy(host = "10.0.0.2")
        val blocker = CompletableDeferred<List<Camera>>()
        val settings = FakeSettings()
        val factory = FakeDriverFactory { cfg ->
            if (cfg == first) blocker.await() else cameras(2)
        }
        val c = controller(settings, factory)

        c.submitSetup(first)
        advanceUntilIdle() // La première connexion est suspendue dans listCameras.
        assertEquals(Screen.Loading, c.state.value.screen)

        c.submitSetup(second)
        advanceUntilIdle()
        blocker.complete(cameras(8)) // Trop tard : la première connexion est annulée.
        advanceUntilIdle()

        val state = c.state.value
        assertEquals(Screen.Grid, state.screen)
        assertEquals(second, state.config)
        assertEquals(cameras(2), state.cameras)
        assertEquals(listOf(second), settings.saved)
        assertEquals("rtsp://10.0.0.2/ch1/MAIN", c.streamUrl(state.cameras[0], StreamQuality.MAIN))
    }

    @Test
    fun `Loading ne traite aucune commande`() = runTest {
        val c = controllerOn(Screen.Loading, 4)
        listOf(Up, Down, Left, Right, Ok, Back, ChannelUp, ChannelDown, Menu, Digit(1)).forEach {
            assertFalse(c.onCommand(it))
        }
    }

    // --- Grille ---------------------------------------------------------------------------------

    @Test
    fun `grille de 8 caméras sur 3 colonnes`() = runTest {
        val c = controllerOn(Screen.Grid, 8)
        fun focus() = c.state.value.focusedIndex
        assertEquals(3, c.state.value.gridColumns)

        assertTrue(c.onCommand(Up)); assertEquals(0, focus())      // bord haut
        assertTrue(c.onCommand(Left)); assertEquals(0, focus())    // bord gauche
        c.onCommand(Down); assertEquals(3, focus())
        c.onCommand(Down); assertEquals(6, focus())
        c.onCommand(Down); assertEquals(6, focus())                // 9 n'existe pas
        c.onCommand(Right); assertEquals(7, focus())
        c.onCommand(Right); assertEquals(7, focus())               // dernière tuile
        c.onCommand(Up); assertEquals(4, focus())
        c.onCommand(Right); assertEquals(5, focus())
        c.onCommand(Down); assertEquals(5, focus())                // 8 n'existe pas
        c.onCommand(Left); assertEquals(4, focus())
        assertEquals(Screen.Grid, c.state.value.screen)
    }

    @Test
    fun `grille de 3 caméras sur 2 colonnes`() = runTest {
        val c = controllerOn(Screen.Grid, 3)
        fun focus() = c.state.value.focusedIndex
        assertEquals(2, c.state.value.gridColumns)

        c.onCommand(Right); assertEquals(1, focus())
        c.onCommand(Down); assertEquals(1, focus())                // 3 n'existe pas
        c.onCommand(Left); assertEquals(0, focus())
        c.onCommand(Down); assertEquals(2, focus())
        c.onCommand(Right); assertEquals(2, focus())
        c.onCommand(Up); assertEquals(0, focus())
    }

    @Test
    fun `chaînes plus et moins bouclent dans la grille`() = runTest {
        val c = controllerOn(Screen.Grid, 3)
        assertTrue(c.onCommand(ChannelDown)); assertEquals(2, c.state.value.focusedIndex)
        assertTrue(c.onCommand(ChannelUp)); assertEquals(0, c.state.value.focusedIndex)
        c.onCommand(ChannelUp); assertEquals(1, c.state.value.focusedIndex)
    }

    @Test
    fun `Ok ouvre la caméra ciblée en plein écran`() = runTest {
        val c = controllerOn(Screen.Grid, 8, focusedIndex = 4)
        assertTrue(c.onCommand(Ok))
        assertEquals(Screen.Fullscreen(5), c.state.value.screen)
    }

    @Test
    fun `chiffre valide ouvre la caméra, les autres sont ignorés`() = runTest {
        val c = controllerOn(Screen.Grid, 3)
        assertTrue(c.onCommand(Digit(0)))
        assertTrue(c.onCommand(Digit(4)))
        assertEquals(Screen.Grid, c.state.value.screen)
        assertEquals(0, c.state.value.focusedIndex)

        assertTrue(c.onCommand(Digit(3)))
        assertEquals(Screen.Fullscreen(3), c.state.value.screen)
        assertEquals(2, c.state.value.focusedIndex)
    }

    @Test
    fun `le chiffre désigne la position et non le numéro de canal`() = runTest {
        val cams = listOf(Camera(2, "a"), Camera(5, "b"), Camera(7, "c"))
        val c = controller(initial = AppState(screen = Screen.Grid, cameras = cams))
        c.onCommand(Digit(2))
        assertEquals(Screen.Fullscreen(5), c.state.value.screen)
        c.onCommand(Right)
        assertEquals(Screen.Fullscreen(7), c.state.value.screen)
        c.onCommand(Back)
        assertEquals(2, c.state.value.focusedIndex)
    }

    @Test
    fun `Menu dans la grille ouvre Setup en gardant la configuration`() = runTest {
        val c = controller(initial = AppState(screen = Screen.Grid, config = config, cameras = cameras(4), error = "ancienne"))
        assertTrue(c.onCommand(Menu))
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals(config, c.state.value.config)
        assertNull(c.state.value.error)
    }

    @Test
    fun `Back dans la grille n'est pas traité`() = runTest {
        val c = controllerOn(Screen.Grid, 4)
        assertFalse(c.onCommand(Back))
        assertEquals(Screen.Grid, c.state.value.screen)
    }

    // --- Plein écran ----------------------------------------------------------------------------

    @Test
    fun `plein écran boucle sur les caméras`() = runTest {
        val c = controllerOn(Screen.Fullscreen(3), 3, focusedIndex = 2)
        assertTrue(c.onCommand(Right)); assertEquals(Screen.Fullscreen(1), c.state.value.screen)
        assertTrue(c.onCommand(Left)); assertEquals(Screen.Fullscreen(3), c.state.value.screen)
        assertTrue(c.onCommand(ChannelDown)); assertEquals(Screen.Fullscreen(2), c.state.value.screen)
        assertTrue(c.onCommand(ChannelUp)); assertEquals(Screen.Fullscreen(3), c.state.value.screen)
        assertTrue(c.onCommand(Digit(1))); assertEquals(Screen.Fullscreen(1), c.state.value.screen)
        assertTrue(c.onCommand(Digit(9))); assertEquals(Screen.Fullscreen(1), c.state.value.screen)
    }

    @Test
    fun `Back depuis le plein écran restaure le focus sur la caméra affichée`() = runTest {
        val c = controllerOn(Screen.Grid, 8)
        c.onCommand(Digit(2))
        c.onCommand(Right)
        c.onCommand(Right)
        assertEquals(Screen.Fullscreen(4), c.state.value.screen)
        assertTrue(c.onCommand(Back))
        assertEquals(Screen.Grid, c.state.value.screen)
        assertEquals(3, c.state.value.focusedIndex)
    }

    @Test
    fun `haut, bas et Ok sont réservés au PTZ en plein écran`() = runTest {
        val c = controllerOn(Screen.Fullscreen(2), 4, focusedIndex = 1)
        listOf(Up, Down, Ok).forEach {
            assertTrue(c.onCommand(it))
            assertEquals(Screen.Fullscreen(2), c.state.value.screen)
        }
    }

    @Test
    fun `Menu en plein écran ouvre Setup`() = runTest {
        val c = controllerOn(Screen.Fullscreen(2), 4)
        assertTrue(c.onCommand(Menu))
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals(config, c.state.value.config)
    }

    // --- Configuration --------------------------------------------------------------------------

    @Test
    fun `Back dans Setup revient à la grille quand des caméras existent`() = runTest {
        val c = controllerOn(Screen.Setup, 4, focusedIndex = 2)
        assertTrue(c.onCommand(Back))
        assertEquals(Screen.Grid, c.state.value.screen)
        assertEquals(2, c.state.value.focusedIndex)
    }

    @Test
    fun `Back dans Setup sans caméra n'est pas traité`() = runTest {
        val c = controllerOn(Screen.Setup, 0)
        assertFalse(c.onCommand(Back))
        assertEquals(Screen.Setup, c.state.value.screen)
    }

    @Test
    fun `Setup laisse le formulaire gérer les autres touches`() = runTest {
        val c = controllerOn(Screen.Setup, 4)
        listOf(Up, Down, Left, Right, Ok, ChannelUp, ChannelDown, Menu, Digit(1)).forEach {
            assertFalse(c.onCommand(it))
        }
        assertEquals(Screen.Setup, c.state.value.screen)
    }

    @Test
    fun `Menu puis Back après connexion revient à la grille`() = runTest {
        val c = controller(settings = FakeSettings(stored = config), factory = FakeDriverFactory { cameras(5) })
        c.start()
        advanceUntilIdle()
        c.onCommand(Menu)
        assertEquals(Screen.Setup, c.state.value.screen)
        assertTrue(c.onCommand(Back))
        assertEquals(Screen.Grid, c.state.value.screen)
    }

    // --- Mode PTZ ------------------------------------------------------------------------------

    private val ptzCameras = listOf(
        Camera(1, "Dôme", ptz = true),
        Camera(2, "Fixe"),
        Camera(3, "Dôme jardin", ptz = true),
    )

    /** Connecté au NVR factice, en plein écran sur la caméra 1, mode PTZ actif. */
    private fun TestScope.ptzController(factory: FakeDriverFactory = FakeDriverFactory { ptzCameras }): AppController {
        val c = controller(settings = FakeSettings(stored = config), factory = factory)
        c.start()
        advanceUntilIdle()
        c.onCommand(Digit(1))
        assertTrue(c.onCommand(Ok))
        assertTrue(c.state.value.ptzMode)
        return c
    }

    @Test
    fun `Ok active le mode PTZ uniquement sur une caméra motorisée`() = runTest {
        val c = controller(initial = AppState(screen = Screen.Fullscreen(2), cameras = ptzCameras, focusedIndex = 1))
        assertTrue(c.onCommand(Ok))
        assertFalse(c.state.value.ptzMode)
        assertEquals(Screen.Fullscreen(2), c.state.value.screen)

        c.onCommand(Right)
        assertEquals(Screen.Fullscreen(3), c.state.value.screen)
        assertTrue(c.onCommand(Ok))
        assertTrue(c.state.value.ptzMode)
        assertEquals(Screen.Fullscreen(3), c.state.value.screen)
    }

    @Test
    fun `haut et bas hors mode PTZ sont consommés sans effet sur une caméra motorisée`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = controller(settings = FakeSettings(stored = config), factory = factory)
        c.start()
        advanceUntilIdle()
        c.onCommand(Digit(1))
        assertTrue(c.onCommand(Up))
        assertTrue(c.onCommand(Down))
        advanceUntilIdle()
        assertFalse(c.state.value.ptzMode)
        assertEquals(Screen.Fullscreen(1), c.state.value.screen)
        assertTrue(factory.ptzCalls.isEmpty())
    }

    @Test
    fun `appui bref sur une flèche, mouvement minimal puis arrêt`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        assertTrue(c.onCommand(Left))
        runCurrent()
        assertEquals(listOf("start:1:LEFT"), factory.ptzCalls)
        assertEquals(Screen.Fullscreen(1), c.state.value.screen) // Gauche ne change plus de caméra.

        c.onCommandReleased(Left)
        runCurrent()
        assertEquals(listOf("start:1:LEFT"), factory.ptzCalls) // Arrêt différé : durée minimale.

        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("start:1:LEFT", "stop:1:LEFT"), factory.ptzCalls)
        assertTrue(c.state.value.ptzMode)

        advanceUntilIdle() // Le minuteur de sécurité ne renvoie pas d'arrêt.
        assertEquals(listOf("start:1:LEFT", "stop:1:LEFT"), factory.ptzCalls)
    }

    @Test
    fun `touche maintenue au-delà de la durée minimale, arrêt immédiat au relâchement`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        c.onCommand(Left)
        advanceTimeBy(700)
        c.onCommandReleased(Left)
        runCurrent()
        assertEquals(listOf("start:1:LEFT", "stop:1:LEFT"), factory.ptzCalls)
    }

    @Test
    fun `nouvel appui pendant la durée minimale, le mouvement continue sans arrêt`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        c.onCommand(Left)
        c.onCommandReleased(Left)
        advanceTimeBy(50)
        c.onCommand(Left)
        advanceTimeBy(600)
        assertEquals(listOf("start:1:LEFT"), factory.ptzCalls)
        c.onCommandReleased(Left)
        runCurrent()
        assertEquals(listOf("start:1:LEFT", "stop:1:LEFT"), factory.ptzCalls)
    }

    @Test
    fun `les répétitions d'une touche maintenue ne relancent ni ne coupent le mouvement`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        c.onCommand(Up)
        advanceTimeBy(500) // Délai avant la première répétition.
        repeat(40) { // 2 s de répétitions toutes les 50 ms.
            assertTrue(c.onCommand(Up))
            advanceTimeBy(50)
        }
        assertEquals(listOf("start:1:UP"), factory.ptzCalls)

        c.onCommandReleased(Up)
        advanceUntilIdle()
        assertEquals(listOf("start:1:UP", "stop:1:UP"), factory.ptzCalls)
    }

    @Test
    fun `relâchement perdu, arrêt automatique après 800 ms`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        c.onCommand(Right)
        advanceTimeBy(799)
        assertEquals(listOf("start:1:RIGHT"), factory.ptzCalls)
        advanceTimeBy(2)
        assertEquals(listOf("start:1:RIGHT", "stop:1:RIGHT"), factory.ptzCalls)

        // Le relâchement tardif est ignoré, l'appui suivant redémarre normalement.
        c.onCommandReleased(Right)
        c.onCommand(Right)
        runCurrent()
        assertEquals(listOf("start:1:RIGHT", "stop:1:RIGHT", "start:1:RIGHT"), factory.ptzCalls)
    }

    @Test
    fun `le relâchement d'une autre touche est ignoré`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        c.onCommand(Left)
        c.onCommandReleased(Up)
        c.onCommandReleased(Ok)
        c.onCommandReleased(Digit(3))
        advanceTimeBy(100)
        assertEquals(listOf("start:1:LEFT"), factory.ptzCalls)
    }

    @Test
    fun `changer de direction arrête d'abord la précédente`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        c.onCommand(Left)
        c.onCommand(Up)
        c.onCommandReleased(Left) // Déjà arrêtée : ignoré.
        runCurrent()
        assertEquals(listOf("start:1:LEFT", "stop:1:LEFT", "start:1:UP"), factory.ptzCalls)
        c.onCommandReleased(Up)
        advanceUntilIdle()
        assertEquals(listOf("start:1:LEFT", "stop:1:LEFT", "start:1:UP", "stop:1:UP"), factory.ptzCalls)
    }

    @Test
    fun `CH plus et CH moins zooment sans changer de caméra`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        assertTrue(c.onCommand(ChannelUp))
        c.onCommandReleased(ChannelUp)
        assertTrue(c.onCommand(ChannelDown))
        c.onCommandReleased(ChannelDown)
        advanceUntilIdle()
        assertEquals(
            listOf("start:1:ZOOM_IN", "stop:1:ZOOM_IN", "start:1:ZOOM_OUT", "stop:1:ZOOM_OUT"),
            factory.ptzCalls,
        )
        assertEquals(Screen.Fullscreen(1), c.state.value.screen)
    }

    @Test
    fun `un chiffre rappelle le préréglage après avoir arrêté le mouvement`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        assertTrue(c.onCommand(Digit(3)))
        c.onCommand(Down)
        assertTrue(c.onCommand(Digit(9)))
        assertTrue(c.onCommand(Digit(0))) // Pas de préréglage 0 : consommé, sans effet.
        advanceUntilIdle()
        assertEquals(listOf("preset:1:3", "start:1:DOWN", "stop:1:DOWN", "preset:1:9"), factory.ptzCalls)
        assertEquals(Screen.Fullscreen(1), c.state.value.screen)
        assertTrue(c.state.value.ptzMode)
    }

    @Test
    fun `Ok quitte le mode PTZ en arrêtant le mouvement et reste en plein écran`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        c.onCommand(Left)
        assertTrue(c.onCommand(Ok))
        runCurrent()
        assertEquals(listOf("start:1:LEFT", "stop:1:LEFT"), factory.ptzCalls)
        assertFalse(c.state.value.ptzMode)
        assertEquals(Screen.Fullscreen(1), c.state.value.screen)

        // Hors mode PTZ, Droite change à nouveau de caméra.
        c.onCommand(Right)
        assertEquals(Screen.Fullscreen(2), c.state.value.screen)
    }

    @Test
    fun `Back quitte le mode PTZ puis un second Back revient à la grille`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        c.onCommand(Up)
        assertTrue(c.onCommand(Back))
        runCurrent()
        assertEquals(listOf("start:1:UP", "stop:1:UP"), factory.ptzCalls)
        assertFalse(c.state.value.ptzMode)
        assertEquals(Screen.Fullscreen(1), c.state.value.screen)

        assertTrue(c.onCommand(Back))
        assertEquals(Screen.Grid, c.state.value.screen)
        assertEquals(0, c.state.value.focusedIndex)
        advanceUntilIdle()
        assertEquals(listOf("start:1:UP", "stop:1:UP"), factory.ptzCalls)
    }

    @Test
    fun `Menu en mode PTZ arrête le mouvement et ouvre Setup`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        c.onCommand(Right)
        assertTrue(c.onCommand(Menu))
        advanceUntilIdle()
        assertEquals(listOf("start:1:RIGHT", "stop:1:RIGHT"), factory.ptzCalls)
        assertEquals(Screen.Setup, c.state.value.screen)
        assertFalse(c.state.value.ptzMode)

        c.onCommand(Back)
        assertEquals(Screen.Grid, c.state.value.screen)
        assertFalse(c.state.value.ptzMode)
    }

    @Test
    fun `une reconnexion arrête le mouvement et quitte le mode PTZ`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        val c = ptzController(factory)
        c.onCommand(Down)
        c.retry()
        advanceUntilIdle()
        assertEquals(listOf("start:1:DOWN", "stop:1:DOWN"), factory.ptzCalls)
        assertEquals(Screen.Grid, c.state.value.screen)
        assertFalse(c.state.value.ptzMode)
    }

    @Test
    fun `une erreur du pilote au démarrage ne bloque pas les ordres suivants`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        var failures = 1
        factory.onPtzStart = { _, _ ->
            if (failures-- > 0) throw CameraDriverException("NVR injoignable")
        }
        val c = ptzController(factory)
        c.onCommand(Left)
        c.onCommandReleased(Left)
        advanceTimeBy(600) // Au-delà de la durée minimale : l'arrêt est parti.
        c.onCommand(Left)
        advanceTimeBy(100)
        assertEquals(listOf("stop:1:LEFT", "start:1:LEFT"), factory.ptzCalls)
        c.onCommandReleased(Left)
        advanceUntilIdle()
        assertEquals(listOf("stop:1:LEFT", "start:1:LEFT", "stop:1:LEFT"), factory.ptzCalls)
        assertTrue(c.state.value.ptzMode)
    }

    @Test
    fun `un pilote lent ne laisse jamais un arrêt doubler son démarrage`() = runTest {
        val factory = FakeDriverFactory { ptzCameras }
        factory.onPtzStart = { _, _ -> delay(300) }
        val c = ptzController(factory)
        c.onCommand(Left)
        c.onCommandReleased(Left)
        c.onCommand(ChannelUp)
        c.onCommandReleased(ChannelUp)
        c.onCommand(Digit(2))
        advanceTimeBy(100)
        assertTrue(factory.ptzCalls.isEmpty()) // Le premier démarrage est encore en cours.
        advanceUntilIdle()
        assertEquals(
            listOf("start:1:LEFT", "stop:1:LEFT", "start:1:ZOOM_IN", "stop:1:ZOOM_IN", "preset:1:2"),
            factory.ptzCalls,
        )
    }

    // --- Snapshots -----------------------------------------------------------------------------

    @Test
    fun `snapshot null tant qu'aucun NVR n'est connecte puis delegue au pilote`() = runTest {
        val c = controller(settings = FakeSettings(stored = config))
        assertNull(c.snapshot(Camera(1, "Porte")))
        c.start()
        advanceUntilIdle()
        assertEquals(0, c.snapshot(Camera(1, "Porte"))?.size)
    }
}
