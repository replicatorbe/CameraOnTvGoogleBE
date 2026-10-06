package be.cameratv.model.driver.dahua

import be.cameratv.model.Camera
import be.cameratv.model.NvrConfig
import be.cameratv.model.PtzDirection
import be.cameratv.model.StreamQuality
import be.cameratv.model.driver.AuthenticationException
import be.cameratv.model.driver.CameraDriverException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

class DahuaCgiDriverTest {

    private lateinit var server: MockWebServer
    private val config = NvrConfig(host = "192.168.1.108", username = "admin", password = "secret")
    private val camera = Camera(channel = 3, name = "Jardin")

    private val challenge =
        """Digest realm="Login to 4L0123PAZ", qop="auth", nonce="1234567890", opaque="op4que""""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun driver(readTimeoutMs: Long = 2_000) = DahuaCgiDriver(
        config,
        OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build(),
        server.url("/"),
    )

    private fun unauthorized() = MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", challenge)

    private inline fun <reified T : Throwable> assertThrowsSuspend(crossinline block: suspend () -> Unit): T {
        try {
            runBlocking { block() }
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Exception inattendue : $e", e)
        }
        fail("${T::class.simpleName} attendue")
        throw IllegalStateException()
    }

    private fun digestParams(header: String): Map<String, String> {
        assertTrue(header, header.startsWith("Digest "))
        return Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""").findAll(header.removePrefix("Digest "))
            .associate { m -> m.groupValues[1] to (m.groups[2]?.value ?: m.groupValues[3]) }
    }

    @Test
    fun `poignee de main digest puis authentification preemptive`() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        server.enqueue(unauthorized())
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)))
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)))

        val d = driver()
        assertArrayEquals(jpeg, d.snapshot(camera))

        assertNull(server.takeRequest().getHeader("Authorization"))
        val authorized = server.takeRequest()
        assertEquals("/cgi-bin/snapshot.cgi?channel=3", authorized.path)
        val p = digestParams(authorized.getHeader("Authorization")!!)
        assertEquals("admin", p["username"])
        assertEquals("Login to 4L0123PAZ", p["realm"])
        assertEquals("1234567890", p["nonce"])
        assertEquals("/cgi-bin/snapshot.cgi?channel=3", p["uri"])
        assertEquals("auth", p["qop"])
        assertEquals("00000001", p["nc"])
        assertEquals("op4que", p["opaque"])
        assertEquals("MD5", p["algorithm"])
        val cnonce = p.getValue("cnonce")
        assertTrue(cnonce.isNotEmpty())
        val expected = DigestAuthenticator.digestResponse(
            "MD5", "admin", "secret", "Login to 4L0123PAZ", "1234567890",
            "GET", "/cgi-bin/snapshot.cgi?channel=3", "auth", "00000001", cnonce,
        )
        assertEquals(expected, p["response"])

        // Deuxième appel : le défi mémorisé évite le 401.
        assertArrayEquals(jpeg, d.snapshot(camera))
        val second = digestParams(server.takeRequest().getHeader("Authorization")!!)
        assertEquals("00000002", second["nc"])
        assertTrue(second["cnonce"] != cnonce)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `nonce expire en preemptif puis nouveau defi`() = runBlocking {
        server.enqueue(unauthorized())
        server.enqueue(MockResponse().setBody("OK"))
        server.enqueue(
            MockResponse().setResponseCode(401)
                .addHeader("WWW-Authenticate", challenge.replace("1234567890", "fresh"))
        )
        server.enqueue(MockResponse().setBody("OK"))

        val d = driver()
        d.gotoPreset(camera, 2)
        d.gotoPreset(camera, 2)
        server.takeRequest(); server.takeRequest(); server.takeRequest()
        val retried = digestParams(server.takeRequest().getHeader("Authorization")!!)
        assertEquals("fresh", retried["nonce"])
        assertEquals("00000001", retried["nc"])
    }

    @Test
    fun `mauvais identifiants`() {
        server.enqueue(unauthorized())
        server.enqueue(unauthorized())
        server.enqueue(unauthorized())

        assertThrowsSuspend<AuthenticationException> { driver().snapshot(camera) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `NVR injoignable`() {
        val d = driver()
        server.shutdown()
        val e = assertThrowsSuspend<CameraDriverException> { d.listCameras() }
        assertTrue(e.message!!, e.message!!.startsWith("NVR injoignable"))
    }

    @Test
    fun `NVR muet`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val e = assertThrowsSuspend<CameraDriverException> { driver(readTimeoutMs = 300).snapshot(camera) }
        assertTrue(e.message!!, e.message!!.startsWith("Le NVR ne répond pas"))
    }

    @Test
    fun `erreur HTTP`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val e = assertThrowsSuspend<CameraDriverException> { driver().listCameras() }
        assertEquals("Erreur du NVR (HTTP 500)", e.message)
    }

    @Test
    fun `liste filtree par l'etat de connexion`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "table.ChannelTitle[0].Name=Porte\r\ntable.ChannelTitle[1].Name=Garage\r\n" +
                    "table.ChannelTitle[2].Name=\r\n"
            )
        )
        server.enqueue(
            MockResponse().setBody(
                "states[0].channel=0\r\nstates[0].connectionState=Connected\r\n" +
                    "states[1].channel=1\r\nstates[1].connectionState=Unconnect\r\n" +
                    "states[2].channel=2\r\nstates[2].connectionState=Connected\r\n"
            )
        )
        server.enqueue(badRequest())

        assertEquals(listOf(Camera(1, "Porte"), Camera(3, "Caméra 3")), driver().listCameras())
        assertEquals(
            "/cgi-bin/configManager.cgi?action=getConfig&name=ChannelTitle",
            server.takeRequest().path,
        )
        assertEquals(
            "/cgi-bin/LogicDeviceManager.cgi?action=getCameraState&uniqueChannels[0]=-1",
            server.takeRequest().path,
        )
        assertEquals(
            "/cgi-bin/configManager.cgi?action=getConfig&name=RemoteDevice",
            server.takeRequest().path,
        )
    }

    @Test
    fun `liste complete si getCameraState indisponible`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("table.ChannelTitle[1].Name=Garage\ntable.ChannelTitle[0].Name=Porte\n")
        )
        server.enqueue(badRequest())
        server.enqueue(badRequest())

        assertEquals(listOf(Camera(1, "Porte"), Camera(2, "Garage")), driver().listCameras())
    }

    @Test
    fun `liste complete si getCameraState illisible`() = runBlocking {
        server.enqueue(MockResponse().setBody("table.ChannelTitle[0].Name=Porte\n"))
        server.enqueue(MockResponse().setBody("something=else\n"))
        server.enqueue(badRequest())

        assertEquals(listOf(Camera(1, "Porte")), driver().listCameras())
    }

    @Test
    fun `liste complete si aucune camera ne semble connectee`() = runBlocking {
        server.enqueue(MockResponse().setBody("table.ChannelTitle[0].Name=Porte\n"))
        server.enqueue(MockResponse().setBody("states[0].channel=0\nstates[0].connectionState=Unknown\n"))
        server.enqueue(badRequest())

        assertEquals(listOf(Camera(1, "Porte")), driver().listCameras())
    }

    private val titlesAndStates = listOf(
        "table.ChannelTitle[0].Name=Allée\ntable.ChannelTitle[3].Name=Porte\n" +
            "table.ChannelTitle[4].Name=Parking\ntable.ChannelTitle[6].Name=Sonnette\n" +
            "table.ChannelTitle[7].Name=Jardin\n",
        "states[0].channel=0\nstates[0].connectionState=Connected\n" +
            "states[1].channel=3\nstates[1].connectionState=Connected\n" +
            "states[2].channel=4\nstates[2].connectionState=Connected\n" +
            "states[3].channel=6\nstates[3].connectionState=Connected\n" +
            "states[4].channel=7\nstates[4].connectionState=Connected\n",
    )

    @Test
    fun `cameras motorisees detectees via RemoteDevice`() = runBlocking {
        titlesAndStates.forEach { server.enqueue(MockResponse().setBody(it)) }
        server.enqueue(
            MockResponse().setBody(
                "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_0.Name=ABC123\r\n" +
                    "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_0.Enable=true\r\n" +
                    "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_0.Address=192.168.1.65\r\n" +
                    "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_0.DeviceType=DH-SD1A404XB-GNR\r\n" +
                    "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_3.DeviceType=IPC-HDBW3241F-AS-M\r\n" +
                    "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_4.DeviceType=DH-SD5A225GB-HNR\r\n" +
                    "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_6.DeviceType=DHI-VTO2211G-WP\r\n" +
                    "table.RemoteDevice.uuid:System_CONFIG_NETCAMERA_INFO_7.DeviceType=DH-SD5A225XA-HNR\r\n"
            )
        )

        assertEquals(
            listOf(
                Camera(1, "Allée", ptz = true),
                Camera(4, "Porte", ptz = false),
                Camera(5, "Parking", ptz = true),
                Camera(7, "Sonnette", ptz = false),
                Camera(8, "Jardin", ptz = true),
            ),
            driver().listCameras(),
        )
        server.takeRequest(); server.takeRequest()
        assertEquals(
            "/cgi-bin/configManager.cgi?action=getConfig&name=RemoteDevice",
            server.takeRequest().path,
        )
    }

    @Test
    fun `aucune camera motorisee si RemoteDevice indisponible`() = runBlocking {
        titlesAndStates.forEach { server.enqueue(MockResponse().setBody(it)) }
        server.enqueue(badRequest())

        val cameras = driver().listCameras()
        assertEquals(listOf(1, 4, 5, 7, 8), cameras.map { it.channel })
        assertTrue(cameras.none { it.ptz })
    }

    @Test
    fun `aucune camera motorisee si RemoteDevice illisible`() = runBlocking {
        titlesAndStates.forEach { server.enqueue(MockResponse().setBody(it)) }
        server.enqueue(MockResponse().setBody("n'importe quoi\r\nfoo.DeviceType=SD49225XA-HNR\r\n"))

        val cameras = driver().listCameras()
        assertEquals(5, cameras.size)
        assertTrue(cameras.none { it.ptz })
    }

    private fun badRequest() = MockResponse().setResponseCode(400).setBody("Error\r\nBad Request!\r\n")

    @Test
    fun `commandes PTZ`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("OK\r\n")) }
        val d = driver()
        d.ptzStart(camera, PtzDirection.ZOOM_IN, 5)
        d.ptzStop(camera, PtzDirection.LEFT)
        assertEquals(
            "/cgi-bin/ptz.cgi?action=start&channel=3&code=ZoomTele&arg1=0&arg2=5&arg3=0",
            server.takeRequest().path,
        )
        assertEquals(
            "/cgi-bin/ptz.cgi?action=stop&channel=3&code=Left&arg1=0&arg2=0&arg3=0",
            server.takeRequest().path,
        )
    }

    @Test
    fun `PTZ refuse`() {
        server.enqueue(MockResponse().setBody("Error\r\n"))
        assertThrowsSuspend<CameraDriverException> { driver().gotoPreset(camera, 1) }
    }

    @Test
    fun `URL RTSP avec identifiants encodes`() {
        val password = "p@ss:w/rd#?% é"
        val d = DahuaCgiDriver(config.copy(username = "admin user", password = password, rtspPort = 5554))
        val url = d.streamUrl(camera, StreamQuality.SUB)

        assertEquals(
            "rtsp://admin%20user:p%40ss%3Aw%2Frd%23%3F%25%20%C3%A9@192.168.1.108:5554" +
                "/cam/realmonitor?channel=3&subtype=1",
            url,
        )
        val uri = URI(url)
        assertEquals("admin user:$password", uri.userInfo)
        assertEquals("192.168.1.108", uri.host)
        assertTrue(d.streamUrl(camera, StreamQuality.MAIN).endsWith("channel=3&subtype=0"))
    }

    @Test
    fun `adresse invalide`() {
        val d = DahuaCgiDriver(config.copy(host = "not a host!"))
        val e = assertThrowsSuspend<CameraDriverException> { d.listCameras() }
        assertTrue(e.message!!.startsWith("Adresse du NVR invalide"))
    }
}
