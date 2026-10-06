package be.cameratv.model.driver.dahua

import be.cameratv.model.Camera
import be.cameratv.model.NvrConfig
import be.cameratv.model.PtzDirection
import be.cameratv.model.StreamQuality
import be.cameratv.model.driver.AuthenticationException
import be.cameratv.model.driver.CameraDriver
import be.cameratv.model.driver.CameraDriverException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Pilote des NVR Dahua via leur API HTTP CGI (authentification Digest) et RTSP. */
class DahuaCgiDriver internal constructor(
    private val config: NvrConfig,
    baseClient: OkHttpClient,
    /** null si l'adresse saisie est invalide : chaque appel réseau échouera proprement. */
    private val baseUrl: HttpUrl?,
) : CameraDriver {

    constructor(config: NvrConfig) : this(config, sharedClient, buildBaseUrl(config))

    private val client: OkHttpClient = DigestAuthenticator(config.username, config.password).let {
        baseClient.newBuilder().authenticator(it).addInterceptor(it).build()
    }

    override suspend fun listCameras(): List<Camera> {
        val cameras = DahuaResponseParser.parseChannelTitles(
            get("cgi-bin/configManager.cgi?action=getConfig&name=ChannelTitle").decodeToString()
        )
        val connected = try {
            DahuaResponseParser.parseConnectedChannels(
                get("cgi-bin/LogicDeviceManager.cgi?action=getCameraState&uniqueChannels[0]=-1")
                    .decodeToString()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // endpoint absent ou non supporté : on garde toutes les caméras titrées
        }
        // Aucune caméra « Connected » : format de réponse probablement non reconnu, on ne filtre pas.
        val filtered = if (connected == null) cameras else cameras.filter { it.channel in connected }
        return filtered.ifEmpty { cameras }
    }

    override fun streamUrl(camera: Camera, quality: StreamQuality): String {
        val subtype = if (quality == StreamQuality.MAIN) 0 else 1
        val user = encodeUserInfo(config.username)
        val pass = encodeUserInfo(config.password)
        return "rtsp://$user:$pass@${hostForUrl(config.host.trim())}:${config.rtspPort}" +
            "/cam/realmonitor?channel=${camera.channel}&subtype=$subtype"
    }

    override suspend fun snapshot(camera: Camera): ByteArray =
        get("cgi-bin/snapshot.cgi?channel=${camera.channel}")

    override suspend fun ptzStart(camera: Camera, direction: PtzDirection, speed: Int) =
        ptz("start", camera, direction.code, arg2 = speed)

    override suspend fun ptzStop(camera: Camera, direction: PtzDirection) =
        ptz("stop", camera, direction.code, arg2 = 0)

    override suspend fun gotoPreset(camera: Camera, preset: Int) =
        ptz("start", camera, "GotoPreset", arg2 = preset)

    private suspend fun ptz(action: String, camera: Camera, code: String, arg2: Int) {
        val body = get(
            "cgi-bin/ptz.cgi?action=$action&channel=${camera.channel}&code=$code&arg1=0&arg2=$arg2&arg3=0"
        ).decodeToString()
        if (!DahuaResponseParser.isOk(body)) {
            throw CameraDriverException("Commande PTZ refusée par le NVR")
        }
    }

    /** GET authentifié ; traduit les erreurs en exceptions du contrat [CameraDriver]. */
    private suspend fun get(pathAndQuery: String): ByteArray {
        val url = baseUrl?.resolve(pathAndQuery)
            ?: throw CameraDriverException("Adresse du NVR invalide (${config.host})")
        val call = client.newCall(Request.Builder().url(url).build())
        return runInterruptible(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    when {
                        response.code == 401 ->
                            throw AuthenticationException("Identifiant ou mot de passe refusé par le NVR")
                        !response.isSuccessful ->
                            throw CameraDriverException("Erreur du NVR (HTTP ${response.code})")
                        else -> response.body?.bytes() ?: ByteArray(0)
                    }
                }
            } catch (e: SocketTimeoutException) {
                throw CameraDriverException("Le NVR ne répond pas (${url.host})", e)
            } catch (e: IOException) {
                throw CameraDriverException("NVR injoignable (${url.host})", e)
            }
        }
    }

    private val PtzDirection.code: String
        get() = when (this) {
            PtzDirection.UP -> "Up"
            PtzDirection.DOWN -> "Down"
            PtzDirection.LEFT -> "Left"
            PtzDirection.RIGHT -> "Right"
            PtzDirection.ZOOM_IN -> "ZoomTele"
            PtzDirection.ZOOM_OUT -> "ZoomWide"
        }

    internal companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        private fun buildBaseUrl(config: NvrConfig): HttpUrl? = try {
            HttpUrl.Builder().scheme("http").host(config.host.trim()).port(config.httpPort).build()
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun hostForUrl(host: String) =
            if (':' in host && !host.startsWith('[')) "[$host]" else host

        /** Encodage pourcent pour la partie userinfo d'une URL : seuls les non-réservés restent tels quels. */
        fun encodeUserInfo(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") {
                    append(c)
                } else {
                    append('%').append("%02X".format(byte.toInt() and 0xFF))
                }
            }
        }
    }
}
