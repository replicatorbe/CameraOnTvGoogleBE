package be.cameratv.controller

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteKeyMapperTest {

    @Test
    fun `table de correspondance des touches`() {
        val expected = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to RemoteCommand.Up,
            KeyEvent.KEYCODE_DPAD_DOWN to RemoteCommand.Down,
            KeyEvent.KEYCODE_DPAD_LEFT to RemoteCommand.Left,
            KeyEvent.KEYCODE_DPAD_RIGHT to RemoteCommand.Right,
            KeyEvent.KEYCODE_DPAD_CENTER to RemoteCommand.Ok,
            KeyEvent.KEYCODE_ENTER to RemoteCommand.Ok,
            KeyEvent.KEYCODE_NUMPAD_ENTER to RemoteCommand.Ok,
            KeyEvent.KEYCODE_BACK to RemoteCommand.Back,
            KeyEvent.KEYCODE_ESCAPE to RemoteCommand.Back,
            KeyEvent.KEYCODE_CHANNEL_UP to RemoteCommand.ChannelUp,
            KeyEvent.KEYCODE_PAGE_UP to RemoteCommand.ChannelUp,
            KeyEvent.KEYCODE_CHANNEL_DOWN to RemoteCommand.ChannelDown,
            KeyEvent.KEYCODE_PAGE_DOWN to RemoteCommand.ChannelDown,
            KeyEvent.KEYCODE_MENU to RemoteCommand.Menu,
            KeyEvent.KEYCODE_SETTINGS to RemoteCommand.Menu,
            KeyEvent.KEYCODE_GUIDE to RemoteCommand.Menu,
            KeyEvent.KEYCODE_INFO to RemoteCommand.Menu,
        )
        expected.forEach { (keyCode, command) ->
            assertEquals("keyCode $keyCode", command, RemoteKeyMapper.map(keyCode))
        }
    }

    @Test
    fun `chiffres du clavier et du pavé numérique`() {
        for (n in 0..9) {
            assertEquals(RemoteCommand.Digit(n), RemoteKeyMapper.map(KeyEvent.KEYCODE_0 + n))
            assertEquals(RemoteCommand.Digit(n), RemoteKeyMapper.map(KeyEvent.KEYCODE_NUMPAD_0 + n))
        }
    }

    @Test
    fun `touches inconnues ignorées`() {
        listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_MEDIA_PLAY, -1)
            .forEach { assertNull(RemoteKeyMapper.map(it)) }
    }
}
