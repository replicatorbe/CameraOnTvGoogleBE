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

/** Configurations du NVR et du broker MQTT stockées dans les Preferences DataStore de l'application. */
class DataStoreSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    override suspend fun load(): NvrConfig? {
        val prefs = readPreferences()
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

    /** Efface la configuration du NVR uniquement ; la configuration MQTT est conservée. */
    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(HOST)
            prefs.remove(USERNAME)
            prefs.remove(PASSWORD)
            prefs.remove(HTTP_PORT)
            prefs.remove(RTSP_PORT)
        }
    }

    override suspend fun loadMqtt(): MqttConfig? {
        val prefs = readPreferences()
        val host = prefs[MQTT_HOST]?.takeIf { it.isNotBlank() } ?: return null
        val defaults = MqttConfig(host)
        return MqttConfig(
            host = host,
            port = prefs[MQTT_PORT] ?: defaults.port,
            baseTopic = prefs[MQTT_BASE_TOPIC] ?: defaults.baseTopic,
            username = prefs[MQTT_USER] ?: defaults.username,
            password = prefs[MQTT_PASSWORD] ?: defaults.password,
        )
    }

    override suspend fun saveMqtt(config: MqttConfig?) {
        dataStore.edit { prefs ->
            if (config == null) {
                prefs.remove(MQTT_HOST)
                prefs.remove(MQTT_PORT)
                prefs.remove(MQTT_BASE_TOPIC)
                prefs.remove(MQTT_USER)
                prefs.remove(MQTT_PASSWORD)
            } else {
                prefs[MQTT_HOST] = config.host
                prefs[MQTT_PORT] = config.port
                prefs[MQTT_BASE_TOPIC] = config.baseTopic
                prefs[MQTT_USER] = config.username
                prefs[MQTT_PASSWORD] = config.password
            }
        }
    }

    private suspend fun readPreferences(): Preferences =
        dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()

    private companion object {
        val HOST = stringPreferencesKey("host")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val HTTP_PORT = intPreferencesKey("http_port")
        val RTSP_PORT = intPreferencesKey("rtsp_port")
        val MQTT_HOST = stringPreferencesKey("mqtt_host")
        val MQTT_PORT = intPreferencesKey("mqtt_port")
        val MQTT_BASE_TOPIC = stringPreferencesKey("mqtt_base_topic")
        val MQTT_USER = stringPreferencesKey("mqtt_user")
        val MQTT_PASSWORD = stringPreferencesKey("mqtt_password")
    }
}
