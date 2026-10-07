package cloud.nalet.chino.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecCapsTest {
    // What a TV with a Dolby decoder decodes, as CodecCaps lists it.
    private val decoded = listOf("avc:2160", "hvc:2160", "av1:2160", "aac", "mp3", "opus", "ac3")

    @Test
    fun `eac3 is sent where the TV plays E-AC-3, decoded or passed through`() {
        assertEquals("avc:2160,hvc:2160,av1:2160,aac,mp3,opus,ac3,eac3", capsQuery(decoded, eac3 = true))
        // A box without a Dolby decoder whose receiver takes E-AC-3 as it is.
        assertEquals("avc:1080,aac,mp3,opus,eac3", capsQuery(listOf("avc:1080", "aac", "mp3", "opus"), eac3 = true))
    }

    @Test
    fun `no eac3 where the TV neither decodes nor passes it through`() {
        assertEquals("avc:2160,hvc:2160,av1:2160,aac,mp3,opus,ac3", capsQuery(decoded, eac3 = false))
        // An eac3 among the decoded tokens is not sent either.
        assertEquals("avc:1080,aac", capsQuery(listOf("avc:1080", "eac3", "aac"), eac3 = false))
        assertEquals("", capsQuery(emptyList(), eac3 = false))
    }

    @Test
    fun `Zap's caps name no surround codec`() {
        assertEquals("avc:2160,hvc:2160,av1:2160,aac,mp3,opus", capsQuery(decoded, eac3 = true, stereo = true))
        assertEquals("avc:2160,hvc:2160,av1:2160,aac,mp3,opus", capsQuery(decoded, eac3 = false, stereo = true))
    }
}
