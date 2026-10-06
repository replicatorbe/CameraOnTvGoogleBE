package be.cameratv.controller

import be.cameratv.model.Camera
import be.cameratv.model.NvrConfig
import be.cameratv.model.PtzDirection
import be.cameratv.model.SettingsRepository
import be.cameratv.model.StreamQuality
import be.cameratv.model.driver.CameraDriver
import be.cameratv.model.driver.CameraDriverFactory

class FakeSettings(var stored: NvrConfig? = null) : SettingsRepository {
    val saved = mutableListOf<NvrConfig>()
    override suspend fun load(): NvrConfig? = stored
    override suspend fun save(config: NvrConfig) {
        saved += config
        stored = config
    }
    override suspend fun clear() {
        stored = null
    }
}

/** Pilote factice : [onList] décide du résultat de listCameras (liste, exception, suspension...). */
class FakeDriver(
    val config: NvrConfig,
    private val onList: suspend (NvrConfig) -> List<Camera>,
) : CameraDriver {
    override suspend fun listCameras(): List<Camera> = onList(config)
    override fun streamUrl(camera: Camera, quality: StreamQuality) =
        "rtsp://${config.host}/ch${camera.channel}/$quality"
    override suspend fun snapshot(camera: Camera) = ByteArray(0)
    override suspend fun ptzStart(camera: Camera, direction: PtzDirection, speed: Int) = Unit
    override suspend fun ptzStop(camera: Camera, direction: PtzDirection) = Unit
    override suspend fun gotoPreset(camera: Camera, preset: Int) = Unit
}

class FakeDriverFactory(var onList: suspend (NvrConfig) -> List<Camera>) : CameraDriverFactory {
    val created = mutableListOf<NvrConfig>()
    override fun create(config: NvrConfig): CameraDriver {
        created += config
        return FakeDriver(config, onList)
    }
}

fun cameras(count: Int) = (1..count).map { Camera(channel = it, name = "Caméra $it") }
