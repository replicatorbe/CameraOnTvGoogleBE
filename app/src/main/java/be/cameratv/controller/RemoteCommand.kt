package be.cameratv.controller

/** Commandes de la télécommande, indépendantes des KeyEvent Android. */
sealed interface RemoteCommand {
    data object Up : RemoteCommand
    data object Down : RemoteCommand
    data object Left : RemoteCommand
    data object Right : RemoteCommand
    data object Ok : RemoteCommand
    data object Back : RemoteCommand
    data object ChannelUp : RemoteCommand
    data object ChannelDown : RemoteCommand
    data object Menu : RemoteCommand
    data class Digit(val value: Int) : RemoteCommand
}
