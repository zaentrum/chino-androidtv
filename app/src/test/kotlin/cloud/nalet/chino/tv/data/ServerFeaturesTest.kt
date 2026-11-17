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
 *  reports and lists by kind, asked once a session. */
class ServerFeaturesTest {
    private val reports = ServerConfigResponse.Features(feedback = true)

    @Test
    fun `the first answer holds for the session`() = runBlocking {
        var asked = 0
        val features = ServerFeatures { asked++; reports }
        assertTrue(features.feedback())
        assertTrue(features.feedback())
        assertFalse(features.watchlistKinds())
        assertEquals(1, asked)
        val none = ServerFeatures { asked++; ServerConfigResponse.Features() }
        assertFalse(none.feedback())
        assertFalse(none.feedback())
        assertEquals(2, asked)
    }

    @Test
    fun `no answer offers nothing for now, and is asked again`() = runBlocking {
        var fail = true
        var asked = 0
        val features = ServerFeatures { asked++; if (fail) throw IOException("no connection") else reports }
        assertFalse(features.feedback())
        fail = false
        assertTrue(features.feedback())
        assertEquals(2, asked)
    }

    @Test
    fun `8_C18 lists by kind are offered only where the server says features watchlist_kinds`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val says = json.decodeFromString(ServerConfigResponse.serializer(), """{"features":{"feedback":false,"watchlist_kinds":true}}""")
        assertTrue(ServerFeatures { says.features }.watchlistKinds())
        val older = json.decodeFromString(ServerConfigResponse.serializer(), """{"features":{"feedback":true}}""")
        assertFalse(ServerFeatures { older.features }.watchlistKinds())
        assertFalse(ServerFeatures { throw IOException("no connection") }.watchlistKinds())
    }

    @Test
    fun `a server older than the field offers no reports`() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString(ServerConfigResponse.serializer(), """{"oidcIssuer":"x","features":{"feedback":true}}""").features.feedback)
        assertFalse(json.decodeFromString(ServerConfigResponse.serializer(), """{"features":{"feedback":false}}""").features.feedback)
        assertFalse(json.decodeFromString(ServerConfigResponse.serializer(), """{"oidcIssuer":"x"}""").features.feedback)
    }
}
