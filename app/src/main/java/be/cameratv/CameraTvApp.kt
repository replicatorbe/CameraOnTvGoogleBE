package be.cameratv

import android.app.Application
import android.content.Intent
import android.provider.Settings
import be.cameratv.controller.AppController
import be.cameratv.controller.ExternalController
import be.cameratv.model.AppModel
import be.cameratv.model.DataStoreSettingsRepository
import be.cameratv.model.bus.PahoMessageBus
import be.cameratv.model.driver.CameraDriverFactory
import be.cameratv.model.driver.dahua.DahuaCgiDriver
import be.cameratv.view.MainActivity
import java.util.UUID
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Racine de composition : Modèle et Contrôleurs vivent ici, aussi longtemps que le processus,
 * et non dans l'activité. Le service MQTT et l'écran s'y branchent tous les deux.
 */
class CameraTvApp : Application() {

    lateinit var model: AppModel
        private set
    lateinit var controller: AppController
        private set
    lateinit var externalController: ExternalController
        private set

    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        model = AppModel()
        val settings = DataStoreSettingsRepository(this)
        val driverFactory = CameraDriverFactory { config -> DahuaCgiDriver(config) }
        controller = AppController(model, settings, driverFactory, scope)
        val bus = PahoMessageBus(clientId = "cameratv-" + stableDeviceId())
        externalController = ExternalController(model, controller, bus, scope)

        controller.start()
        bringToFrontOnRequest()
        CameraTvService.start(this)
    }

    /**
     * Commande externe reçue pendant qu'une autre application est affichée : on ouvre l'écran.
     * Android 10+ ne l'autorise depuis l'arrière-plan qu'avec la permission
     * « afficher par-dessus les autres applications » (accordée par adb sur Google TV).
     */
    private fun bringToFrontOnRequest() {
        scope.launch {
            model.state
                .map { it.foregroundRequested }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    startActivity(
                        Intent(this@CameraTvApp, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
        }
    }

    /** Identifiant MQTT stable d'un démarrage à l'autre ; aléatoire si Android ne le fournit pas. */
    private fun stableDeviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
}
