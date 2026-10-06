package be.cameratv.model

/** Persistance de la configuration du NVR. */
interface SettingsRepository {
    suspend fun load(): NvrConfig?
    suspend fun save(config: NvrConfig)
    suspend fun clear()
}
