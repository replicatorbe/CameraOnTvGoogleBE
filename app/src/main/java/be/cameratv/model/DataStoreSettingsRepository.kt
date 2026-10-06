package be.cameratv.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Configuration du NVR stockée dans les Preferences DataStore de l'application. */
class DataStoreSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    override suspend fun load(): NvrConfig? {
        val prefs = dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()
        val host = prefs[HOST]?.takeIf { it.isNotBlank() } ?: return null
        return NvrConfig(
            host = host,
            username = prefs[USERNAME].orEmpty(),
            password = prefs[PASSWORD].orEmpty(),
            httpPort = prefs[HTTP_PORT] ?: 80,
            rtspPort = prefs[RTSP_PORT] ?: 554,
        )
    }

    override suspend fun save(config: NvrConfig) {
        dataStore.edit {
            it[HOST] = config.host
            it[USERNAME] = config.username
            it[PASSWORD] = config.password
            it[HTTP_PORT] = config.httpPort
            it[RTSP_PORT] = config.rtspPort
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val HOST = stringPreferencesKey("host")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val HTTP_PORT = intPreferencesKey("http_port")
        val RTSP_PORT = intPreferencesKey("rtsp_port")
    }

    // STUB : implémentation à venir.
    override suspend fun loadMqtt(): MqttConfig? = TODO()
    override suspend fun saveMqtt(config: MqttConfig?): Unit = TODO()
}
