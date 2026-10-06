package be.cameratv.model.driver

import be.cameratv.model.Camera
import be.cameratv.model.NvrConfig
import be.cameratv.model.PtzDirection
import be.cameratv.model.StreamQuality

/**
 * Pilote d'un système de caméras. Le reste de l'application ne connaît que cette interface :
 * Dahua CGI aujourd'hui, ONVIF demain, sans toucher aux vues ni aux contrôleurs.
 */
interface CameraDriver {
    /** Liste les caméras disponibles. Lève [CameraDriverException] en cas d'échec. */
    suspend fun listCameras(): List<Camera>

    /** URL RTSP, identifiants inclus et encodés, directement lisible par ExoPlayer. */
    fun streamUrl(camera: Camera, quality: StreamQuality): String

    /** Image JPEG instantanée. */
    suspend fun snapshot(camera: Camera): ByteArray

    suspend fun ptzStart(camera: Camera, direction: PtzDirection, speed: Int = 4)
    suspend fun ptzStop(camera: Camera, direction: PtzDirection)
    suspend fun gotoPreset(camera: Camera, preset: Int)
}

fun interface CameraDriverFactory {
    fun create(config: NvrConfig): CameraDriver
}

class CameraDriverException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Levée quand le NVR refuse les identifiants (HTTP 401 persistant). */
class AuthenticationException(message: String) : Exception(message)
