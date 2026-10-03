package cloud.nalet.chino.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// chino-web's src/lib/artwork.test.ts, plus resolving chino-api's root-relative
// paths against the API base the TV client holds.
class ArtworkUrlTest {
    private val base = "https://media.example/api"

    @Test
    fun `a path chino-api hands out resolves against the API base`() {
        assertEquals(
            "https://media.example/api/v1/people/p1/profile?stream=tok",
            streamArtworkUrl(base, "/api/v1/people/p1/profile", "tok"),
        )
        assertEquals(
            "https://media.example/api/v1/people/p1/profile?stream=tok",
            streamArtworkUrl("$base/", "/api/v1/people/p1/profile", "tok"),
        )
        assertEquals("https://cdn.example/p1.jpg?stream=tok", streamArtworkUrl(base, "https://cdn.example/p1.jpg", "tok"))
    }

    @Test
    fun `the stream token rides on the URL, encoded`() {
        assertEquals(
            "https://media.example/api/v1/people/p1/profile?stream=a%20b%2Bc",
            streamArtworkUrl(base, "/api/v1/people/p1/profile", "a b+c"),
        )
        assertEquals(
            "https://media.example/api/v1/people/p1/profile?w=185&stream=tok",
            streamArtworkUrl(base, "/api/v1/people/p1/profile?w=185", "tok"),
        )
        // A real token — base64url payload, a dot, base64url signature — goes as it is.
        assertEquals(
            "https://media.example/api/v1/people/p1/profile?stream=dXNlcnwxNzU5.c2ln-_",
            streamArtworkUrl(base, "/api/v1/people/p1/profile", "dXNlcnwxNzU5.c2ln-_"),
        )
    }

    @Test
    fun `no token yet - the URL as it is, no path - nothing`() {
        assertEquals("https://media.example/api/v1/people/p1/profile", streamArtworkUrl(base, "/api/v1/people/p1/profile", null))
        assertEquals("https://media.example/api/v1/people/p1/profile", streamArtworkUrl(base, "/api/v1/people/p1/profile", ""))
        assertNull(streamArtworkUrl(base, null, "tok"))
        assertNull(streamArtworkUrl(base, "  ", "tok"))
    }
}
