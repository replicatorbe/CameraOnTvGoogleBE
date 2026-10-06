package be.cameratv.view

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import be.cameratv.controller.AppController
import be.cameratv.controller.RemoteCommand
import be.cameratv.controller.RemoteKeyMapper
import be.cameratv.model.AppModel
import be.cameratv.BuildConfig
import be.cameratv.model.DataStoreSettingsRepository
import be.cameratv.model.NvrConfig
import be.cameratv.model.Screen
import be.cameratv.model.driver.CameraDriverFactory
import be.cameratv.model.driver.dahua.DahuaCgiDriver
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Racine de composition : assemble Modèle, Contrôleur et Vue (injection manuelle),
 * puis transmet les touches de la télécommande au contrôleur.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: AppController

    /** Touches dont l'ACTION_DOWN a été consommé : on consomme aussi leur ACTION_UP. */
    private val consumedKeyCodes = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val model = AppModel()
        val settings = DataStoreSettingsRepository(applicationContext)
        val driverFactory = CameraDriverFactory { config -> DahuaCgiDriver(config) }
        controller = AppController(model, settings, driverFactory, lifecycleScope)
        val debugConfig = debugConfigFromIntent()
        if (debugConfig != null) controller.submitSetup(debugConfig) else controller.start()

        keepScreenOnWhileWatching()

        setContent {
            CameraTvTheme {
                AppView(controller)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val command = RemoteKeyMapper.map(event.keyCode)
                if (command != null && shouldForward(command) && controller.onCommand(command)) {
                    consumedKeyCodes += event.keyCode
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                if (consumedKeyCodes.remove(event.keyCode)) return true
            }
        }
        // Non géré par le contrôleur : comportement Android normal
        // (Retour quitte l'app depuis la grille, focus Compose dans le formulaire).
        return super.dispatchKeyEvent(event)
    }

    /**
     * Sur l'écran de configuration, les flèches, OK et les chiffres servent à la saisie
     * et à la navigation Compose : seuls Retour et Menu vont au contrôleur.
     */
    private fun shouldForward(command: RemoteCommand): Boolean =
        controller.state.value.screen != Screen.Setup ||
            command == RemoteCommand.Back || command == RemoteCommand.Menu

    /** Écran toujours allumé pendant qu'on regarde des caméras, sinon veille normale. */
    private fun keepScreenOnWhileWatching() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state
                    .map { it.screen is Screen.Grid || it.screen is Screen.Fullscreen }
                    .distinctUntilChanged()
                    .collect { watching ->
                        if (watching) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
            }
        }
    }

    /**
     * Build debug uniquement : configuration passée par adb, le clavier TV rendant la saisie pénible.
     * adb shell am start -n be.cameratv/.view.MainActivity --es nvr_host IP --es nvr_user U --es nvr_password P
     */
    private fun debugConfigFromIntent(): NvrConfig? {
        if (!BuildConfig.DEBUG) return null
        val host = intent.getStringExtra("nvr_host") ?: return null
        return NvrConfig(
            host = host,
            username = intent.getStringExtra("nvr_user").orEmpty(),
            password = intent.getStringExtra("nvr_password").orEmpty(),
        )
    }
}
