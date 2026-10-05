package cloud.nalet.chino.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule the container hands Telemetry and the notices the API by: with no
 * server connected there is no API client, and none is built. A fresh install
 * has no server until Add Server and a release build's built-in address is
 * empty; building the client then crashed the app at start (fc7f983).
 */
class OnceConnectedTest {
    private var built = 0

    private fun api(): String {
        built++
        return "api"
    }

    @Test
    fun `no server - no API client, and none built`() = runBlocking {
        assertNull(onceConnected({ null }, ::api))
        // A release build's fallback: an empty address is no server.
        assertNull(onceConnected({ ServerConfig(baseUrl = "", issuer = "", clientId = "chino") }, ::api))
        assertNull(onceConnected({ ServerConfig(baseUrl = "  ", issuer = "", clientId = "chino") }, ::api))
        assertEquals(0, built)
    }

    @Test
    fun `a server connected - the API`() = runBlocking {
        val server = ServerConfig(
            baseUrl = "https://media.example.org/api/",
            issuer = "https://id.example.org/realms/media",
            clientId = "chino",
        )
        assertEquals("api", onceConnected({ server }, ::api))
        assertEquals(1, built)
    }
}
