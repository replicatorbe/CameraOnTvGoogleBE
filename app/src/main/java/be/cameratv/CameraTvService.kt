package be.cameratv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Service au premier plan : garde la connexion MQTT ouverte quand la TV affiche une autre
 * application, pour que la domotique puisse afficher une caméra à tout moment.
 */
class CameraTvService : Service() {

    private val app get() = application as CameraTvApp

    /**
     * Entrée et sortie de veille : l'état de l'écran est publié, et au rallumage la connexion MQTT
     * est renouvelée par sécurité.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            app.controller.onScreenChanged(on)
            if (on) app.externalController.onNetworkMaybeRestored()
        }
    }

    /** Retour du réseau après une coupure (le premier rappel, à l'enregistrement, est ignoré). */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var lost = false

        override fun onLost(network: Network) {
            lost = true
        }

        override fun onAvailable(network: Network) {
            if (lost) {
                lost = false
                app.externalController.onNetworkMaybeRestored()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        app.externalController.start()
        app.controller.onScreenChanged(getSystemService(PowerManager::class.java).isInteractive)
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        app.externalController.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pilotage domotique", NotificationManager.IMPORTANCE_MIN)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("En attente des commandes de la domotique")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "domotique"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CameraTvService::class.java))
        }
    }
}
