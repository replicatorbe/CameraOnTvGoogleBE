package be.cameratv.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val repository by lazy {
        DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                folder.root.resolve("settings.preferences_pb")
            }
        )
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `vide au depart`() = runBlocking {
        assertNull(repository.load())
    }

    @Test
    fun `sauvegarde, relecture et effacement`() = runBlocking {
        val config = NvrConfig("192.168.1.108", "admin", "p@ss:w/rd", httpPort = 8080, rtspPort = 5554)
        repository.save(config)
        assertEquals(config, repository.load())
        repository.clear()
        assertNull(repository.load())
    }
}
