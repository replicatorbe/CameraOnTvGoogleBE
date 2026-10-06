package be.cameratv.model.driver.dahua

import be.cameratv.model.Camera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DahuaResponseParserTest {

    @Test
    fun `titres de canaux avec CRLF, lignes vides, cles en trop et noms vides`() {
        val body = "table.ChannelTitle[2].Name=Jardin\r\n" +
            "\r\n" +
            "table.ChannelTitle[0].Name=Porte d'entrée\r\n" +
            "table.ChannelTitle[1].Name=\r\n" +
            "table.ChannelTitle[1].Extra=ignored\r\n" +
            "garbage line\r\n"

        assertEquals(
            listOf(
                Camera(1, "Porte d'entrée"),
                Camera(2, "Caméra 2"),
                Camera(3, "Jardin"),
            ),
            DahuaResponseParser.parseChannelTitles(body),
        )
    }

    @Test
    fun `valeur contenant un signe egal`() {
        assertEquals(
            listOf("a" to "b=c"),
            DahuaResponseParser.parseKeyValues("a=b=c\n"),
        )
    }

    @Test
    fun `corps d'erreur sans titres`() {
        assertTrue(DahuaResponseParser.parseChannelTitles("Error\r\nBad Request!\r\n").isEmpty())
    }

    @Test
    fun `etats de connexion`() {
        val body = "states[0].channel=0\r\n" +
            "states[0].connectionState=Connected\r\n" +
            "states[1].channel=1\r\n" +
            "states[1].connectionState=Unconnect\r\n" +
            "states[2].channel=4\r\n" +
            "states[2].connectionState=Connected\r\n" +
            "states[2].other=x\r\n"

        assertEquals(setOf(1, 5), DahuaResponseParser.parseConnectedChannels(body))
    }

    @Test
    fun `etats illisibles donnent null`() {
        assertNull(DahuaResponseParser.parseConnectedChannels("Error\r\nBad Request!"))
        assertNull(DahuaResponseParser.parseConnectedChannels("states[0].channel=0"))
    }

    @Test
    fun `reponse OK`() {
        assertTrue(DahuaResponseParser.isOk("OK\r\n"))
        assertFalse(DahuaResponseParser.isOk("Error"))
    }
}
