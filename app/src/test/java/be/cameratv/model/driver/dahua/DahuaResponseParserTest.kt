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
    fun `references motorisees`() {
        listOf(
            "DH-SD1A404XB-GNR",
            "DH-SD5A225GB-HNR",
            "DH-SD5A225XA-HNR",
            "DH-SD22204DB-GNY",
            "SD49225XA-HNR",
            "DHI-SD6C3432XB-HNR",
            " dh-sd1a203t-gn ",
            "Generic-PTZ-Camera",
            "ptz200",
        ).forEach { assertTrue(it, DahuaResponseParser.isPtzDeviceType(it)) }
    }

    @Test
    fun `references fixes ou illisibles`() {
        listOf(
            "IPC-HDBW3241F-AS-M",
            "IPC-HFW2431S",
            "DHI-VTO2211G-WP",
            "DH-IPC-HDW2431T",
            "",
            "   ",
            "???",
            "DH-",
            "HDCVI-SDX",
        ).forEach { assertFalse(it, DahuaResponseParser.isPtzDeviceType(it)) }
    }

    @Test
    fun `canaux motorises depuis RemoteDevice`() {
        val body = "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_0.Name=ABC123\r\n" +
            "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_0.Address=192.168.1.65\r\n" +
            "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_0.DeviceType=DH-SD1A404XB-GNR\r\n" +
            "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_3.DeviceType=IPC-HDBW3241F-AS-M\r\n" +
            "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_4.DeviceType=DH-SD5A225GB-HNR\r\n" +
            "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_6.DeviceType=DHI-VTO2211G-WP\r\n" +
            "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_7.DeviceType=DH-SD5A225XA-HNR\r\n" +
            "table.RemoteDevice.Other_10.DeviceType=SD49225XA-HNR\r\n" +
            "table.RemoteDevice[11].DeviceType=DH-SD22204DB-GNY\r\n" +
            "table.RemoteDevice.uuid:sans_index.DeviceType=DH-SD22204DB-GNY\r\n" +
            "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_x.DeviceType=DH-SD22204DB-GNY\r\n" +
            "garbage\r\n"

        assertEquals(setOf(1, 5, 8, 11, 12), DahuaResponseParser.parsePtzChannels(body))
    }

    @Test
    fun `RemoteDevice illisible donne aucun canal motorise`() {
        assertTrue(DahuaResponseParser.parsePtzChannels("Error\r\nBad Request!\r\n").isEmpty())
        assertTrue(DahuaResponseParser.parsePtzChannels("").isEmpty())
    }

    @Test
    fun `reponse OK`() {
        assertTrue(DahuaResponseParser.isOk("OK\r\n"))
        assertFalse(DahuaResponseParser.isOk("Error"))
    }
}
