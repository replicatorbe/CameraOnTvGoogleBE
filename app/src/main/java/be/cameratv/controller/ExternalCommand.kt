package be.cameratv.controller

import be.cameratv.model.PtzDirection

/** Désignation d'une caméra par la domotique : numéro de canal (1..8) ou nom (insensible à la casse). */
sealed interface CameraRef {
    data class ByChannel(val channel: Int) : CameraRef
    data class ByName(val name: String) : CameraRef
}

sealed interface PtzAction {
    data class Move(val direction: PtzDirection, val durationMs: Long) : PtzAction
    data class Preset(val preset: Int) : PtzAction
    data object Stop : PtzAction
}

/** Commandes reçues de l'extérieur (MQTT, lien profond), indépendantes du transport. */
sealed interface ExternalCommand {
    /** Plein écran sur une caméra ; après [durationSec] secondes, retour à l'écran précédent. */
    data class ShowCamera(val camera: CameraRef, val durationSec: Int? = null) : ExternalCommand
    data object ShowGrid : ExternalCommand
    data class Ptz(val camera: CameraRef, val action: PtzAction) : ExternalCommand
    /** Met l'application en arrière-plan (retour à ce que la TV affichait avant). */
    data object Exit : ExternalCommand
}
