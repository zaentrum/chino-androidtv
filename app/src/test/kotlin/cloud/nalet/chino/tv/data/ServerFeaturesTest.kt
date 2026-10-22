package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ServerConfigResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** What the server offers that the app shows only where it does: bug
 *  reports, asked once a session. */
class ServerFeaturesTest {
    @Test
    fun `the first answer holds for the session`() = runBlocking {
        var asked = 0
        val features = ServerFeatures { asked++; true }
        assertTrue(features.feedback())
        assertTrue(features.feedback())
        assertEquals(1, asked)
        val none = ServerFeatures { asked++; false }
        assertFalse(none.feedback())
        assertFalse(none.feedback())
        assertEquals(2, asked)
    }

    @Test
    fun `no answer offers nothing for now, and is asked again`() = runBlocking {
        var fail = true
        var asked = 0
        val features = ServerFeatures { asked++; if (fail) throw IOException("no connection") else true }
        assertFalse(features.feedback())
        fail = false
        assertTrue(features.feedback())
        assertEquals(2, asked)
    }

    @Test
    fun `a server older than the field offers no reports`() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString(ServerConfigResponse.serializer(), """{"oidcIssuer":"x","features":{"feedback":true}}""").features.feedback)
        assertFalse(json.decodeFromString(ServerConfigResponse.serializer(), """{"features":{"feedback":false}}""").features.feedback)
        assertFalse(json.decodeFromString(ServerConfigResponse.serializer(), """{"oidcIssuer":"x"}""").features.feedback)
    }
}
