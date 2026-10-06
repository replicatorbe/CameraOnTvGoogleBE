package be.cameratv.controller

import be.cameratv.model.Camera
import be.cameratv.model.MqttConfig
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

    var storedMqtt: MqttConfig? = null
    override suspend fun loadMqtt(): MqttConfig? = storedMqtt
    override suspend fun saveMqtt(config: MqttConfig?) {
        storedMqtt = config
    }
}

/**
 * Pilote factice : [onList] décide du résultat de listCameras (liste, exception, suspension...).
 * Les ordres PTZ sont consignés dans [ptzCalls] à la fin de leur exécution
 * ("start:1:LEFT", "stop:1:LEFT", "preset:1:3"). [onPtzStart] permet de ralentir ou de faire
 * échouer un démarrage (rien n'est alors consigné).
 */
class FakeDriver(
    val config: NvrConfig,
    private val onList: suspend (NvrConfig) -> List<Camera>,
    private val ptzCalls: MutableList<String> = mutableListOf(),
    private val onPtzStart: suspend (Camera, PtzDirection) -> Unit = { _, _ -> },
) : CameraDriver {
    override suspend fun listCameras(): List<Camera> = onList(config)
    override fun streamUrl(camera: Camera, quality: StreamQuality) =
        "rtsp://${config.host}/ch${camera.channel}/$quality"
    override suspend fun snapshot(camera: Camera) = ByteArray(0)
    override suspend fun ptzStart(camera: Camera, direction: PtzDirection, speed: Int) {
        onPtzStart(camera, direction)
        ptzCalls += "start:${camera.channel}:$direction"
    }
    override suspend fun ptzStop(camera: Camera, direction: PtzDirection) {
        ptzCalls += "stop:${camera.channel}:$direction"
    }
    override suspend fun gotoPreset(camera: Camera, preset: Int) {
        ptzCalls += "preset:${camera.channel}:$preset"
    }
}

class FakeDriverFactory(var onList: suspend (NvrConfig) -> List<Camera>) : CameraDriverFactory {
    val created = mutableListOf<NvrConfig>()

    /** Ordres PTZ reçus par tous les pilotes créés, dans l'ordre. */
    val ptzCalls = mutableListOf<String>()
    var onPtzStart: suspend (Camera, PtzDirection) -> Unit = { _, _ -> }

    override fun create(config: NvrConfig): CameraDriver {
        created += config
        return FakeDriver(config, onList, ptzCalls) { camera, direction -> onPtzStart(camera, direction) }
    }
}

fun cameras(count: Int) = (1..count).map { Camera(channel = it, name = "Caméra $it") }
