package be.cameratv.model

/** Paramètres de connexion au NVR, saisis sur l'écran de configuration. */
data class NvrConfig(
    val host: String,
    val username: String,
    val password: String,
    val httpPort: Int = 80,
    val rtspPort: Int = 554,
)
