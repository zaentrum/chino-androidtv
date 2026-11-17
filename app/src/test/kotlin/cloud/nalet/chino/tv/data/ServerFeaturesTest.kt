package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ServerConfigResponse
import kotlinx.coroutines.CompletableDeferred
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

    /** Features with nothing kept on the device of what the server said
     *  before, keeping nothing of what it says. */
    private fun featuresOf(fetch: suspend () -> ServerConfigResponse.Features) =
        ServerFeatures(kept = null, keep = {}, fetch = fetch)

    @Test
    fun `the first answer holds for the session`() = runBlocking {
        var asked = 0
        val features = featuresOf { asked++; reports }
        assertTrue(features.feedback())
        assertTrue(features.feedback())
        assertFalse(features.watchlistKinds())
        assertEquals(1, asked)
        val none = featuresOf { asked++; ServerConfigResponse.Features() }
        assertFalse(none.feedback())
        assertFalse(none.feedback())
        assertEquals(2, asked)
    }

    @Test
    fun `no answer offers nothing for now, and is asked again`() = runBlocking {
        var fail = true
        var asked = 0
        val features = featuresOf { asked++; if (fail) throw IOException("no connection") else reports }
        assertFalse(features.feedback())
        fail = false
        assertTrue(features.feedback())
        assertEquals(2, asked)
    }

    @Test
    fun `8_C18 lists by kind are offered only where the server says features watchlist_kinds`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val says = json.decodeFromString(ServerConfigResponse.serializer(), """{"features":{"feedback":false,"watchlist_kinds":true}}""")
        assertTrue(featuresOf { says.features }.watchlistKinds())
        val older = json.decodeFromString(ServerConfigResponse.serializer(), """{"features":{"feedback":true}}""")
        assertFalse(featuresOf { older.features }.watchlistKinds())
        assertFalse(featuresOf { throw IOException("no connection") }.watchlistKinds())
    }

    @Test
    fun `a server older than the field offers no reports`() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString(ServerConfigResponse.serializer(), """{"oidcIssuer":"x","features":{"feedback":true}}""").features.feedback)
        assertFalse(json.decodeFromString(ServerConfigResponse.serializer(), """{"features":{"feedback":false}}""").features.feedback)
        assertFalse(json.decodeFromString(ServerConfigResponse.serializer(), """{"oidcIssuer":"x"}""").features.feedback)
    }

    @Test
    fun `8_8_1 what the server said before of lists by kind is known before it answers, and where its answer fails`() = runBlocking {
        val kept = mutableListOf<Boolean>()
        val answer = CompletableDeferred<ServerConfigResponse.Features>()
        val slow = ServerFeatures(kept = true, keep = { kept += it }) { answer.await() }
        assertTrue(slow.watchlistKindsNow())
        assertTrue(slow.watchlistKindsChanges.value)
        val failed = ServerFeatures(kept = true, keep = { kept += it }) { throw IOException("no connection") }
        assertTrue(failed.watchlistKinds())
        assertTrue(failed.watchlistKindsNow())
        assertEquals(emptyList<Boolean>(), kept)
        answer.complete(ServerConfigResponse.Features(watchlistKinds = true))
        assertTrue(slow.watchlistKinds())
        // The same as kept: nothing to keep again.
        assertEquals(emptyList<Boolean>(), kept)
    }

    @Test
    fun `8_8_1 an answer that differs from what was kept is followed and kept`() = runBlocking {
        val kept = mutableListOf<Boolean>()
        val features = ServerFeatures(kept = true, keep = { kept += it }) { ServerConfigResponse.Features(watchlistKinds = false) }
        assertTrue(features.watchlistKindsNow())
        assertFalse(features.watchlistKinds())
        assertFalse(features.watchlistKindsNow())
        assertFalse(features.watchlistKindsChanges.value)
        assertEquals(listOf(false), kept)
        // Nothing kept: off until the server says, then kept.
        val fresh = ServerFeatures(kept = null, keep = { kept += it }) { ServerConfigResponse.Features(watchlistKinds = true) }
        assertFalse(fresh.watchlistKindsNow())
        assertTrue(fresh.watchlistKinds())
        assertEquals(listOf(false, true), kept)
    }

    @Test
    fun `8_8_1 a copy that couldn't be kept leaves the answer standing`() = runBlocking {
        val features = ServerFeatures(kept = null, keep = { throw IOException("disk full") }) {
            ServerConfigResponse.Features(watchlistKinds = true)
        }
        assertTrue(features.watchlistKinds())
        assertTrue(features.watchlistKindsNow())
    }
}
