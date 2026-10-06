package be.cameratv.model

/** Broker MQTT par lequel la domotique (Jeedom) pilote l'application. Absent = pilotage désactivé. */
data class MqttConfig(
    val host: String,
    val port: Int = 1883,
    /** Préfixe des topics : `<baseTopic>/cmd/...`, `<baseTopic>/state`, `<baseTopic>/online`. */
    val baseTopic: String = "cameratv",
    val username: String = "",
    val password: String = "",
)
