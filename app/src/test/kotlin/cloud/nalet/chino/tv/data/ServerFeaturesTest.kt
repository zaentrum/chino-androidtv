package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ServerConfigResponse
import cloud.nalet.chino.tv.data.api.ServerConfigResponse.Features
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** What the server offers that the app shows only where it does: bug
 *  reports and a watchlist split by kind, asked once a session. */
class ServerFeaturesTest {
    @Test
    fun `the first answer holds for the session`() = runBlocking {
        var asked = 0
        val features = ServerFeatures { asked++; Features(feedback = true) }
        assertTrue(features.feedback())
        assertTrue(features.feedback())
        assertEquals(1, asked)
        val none = ServerFeatures { asked++; Features() }
        assertFalse(none.feedback())
        assertFalse(none.feedback())
        assertEquals(2, asked)
    }

    @Test
    fun `no answer offers nothing for now, and is asked again`() = runBlocking {
        var fail = true
        var asked = 0
        val features = ServerFeatures { asked++; if (fail) throw IOException("no connection") else Features(feedback = true) }
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

    @Test
    fun `a watchlist by kind needs both the setting and the filter`() = runBlocking {
        assertTrue(ServerFeatures { Features(preferences = true, watchlistKinds = true) }.watchlistByKind())
        assertFalse(ServerFeatures { Features(preferences = true) }.watchlistByKind())
        assertFalse(ServerFeatures { Features(watchlistKinds = true) }.watchlistByKind())
        assertFalse(ServerFeatures { Features() }.watchlistByKind())
    }

    @Test
    fun `the features are read by the server's names, and an older server offers neither`() {
        val json = Json { ignoreUnknownKeys = true }
        val both = json.decodeFromString(
            ServerConfigResponse.serializer(),
            """{"features":{"downloads":true,"preferences":true,"watchlist_kinds":true}}""",
        ).features
        assertTrue(both.preferences)
        assertTrue(both.watchlistKinds)
        val older = json.decodeFromString(ServerConfigResponse.serializer(), """{"features":{"downloads":true}}""").features
        assertFalse(older.preferences)
        assertFalse(older.watchlistKinds)
    }
}
