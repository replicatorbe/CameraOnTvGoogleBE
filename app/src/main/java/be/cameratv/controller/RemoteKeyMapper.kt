package be.cameratv.controller

import android.view.KeyEvent

/** Traduit les codes de touches Android (télécommande, clavier) en [RemoteCommand]. */
object RemoteKeyMapper {

    /** Retourne null pour une touche que l'application ne gère pas. */
    fun map(keyCode: Int): RemoteCommand? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> RemoteCommand.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> RemoteCommand.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> RemoteCommand.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteCommand.Right

        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteCommand.Ok

        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE -> RemoteCommand.Back

        KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_PAGE_UP -> RemoteCommand.ChannelUp

        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_PAGE_DOWN -> RemoteCommand.ChannelDown

        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_INFO -> RemoteCommand.Menu

        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            RemoteCommand.Digit(keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
            RemoteCommand.Digit(keyCode - KeyEvent.KEYCODE_NUMPAD_0)

        else -> null
    }
}
