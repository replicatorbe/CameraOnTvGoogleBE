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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
