package be.cameratv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Démarrage de la TV : le service MQTT démarre sans attendre qu'on ouvre l'application. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // L'Application est déjà créée à ce stade et démarre le service ; on le relance par sûreté.
            CameraTvService.start(context)
        }
    }
}
