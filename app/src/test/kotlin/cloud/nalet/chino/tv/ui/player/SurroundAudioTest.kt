package cloud.nalet.chino.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SurroundAudioTest {
    private val q = "?stream=t&caps=avc:2160,hvc:2160,aac,mp3,opus,ac3,eac3&q=auto&v=38748908"

    // A packaged title with a 5.1 companion as chino-stream serves it to a TV
    // whose caps name eac3: one variant, one audio group, the companion just
    // before its stereo twin and DEFAULT (Sintel on the public demo).
    private fun master(vararg audio: String) = """
        |#EXTM3U
        |#EXT-X-INDEPENDENT-SEGMENTS
        |
        |${audio.joinToString("\n")}
        |
        |#EXT-X-STREAM-INF:BANDWIDTH=9227455,AVERAGE-BANDWIDTH=5149947,CODECS="hvc1.1.2.L120.90,ec-3,mp4a.40.2",RESOLUTION=1920x818,FRAME-RATE=24.000,VIDEO-RANGE=SDR,AUDIO="audio-surround",CLOSED-CAPTIONS=NONE
        |v0/playlist.m3u8$q
        |
        |#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=818736,AVERAGE-BANDWIDTH=231699,CODECS="hvc1.1.2.L120.90",RESOLUTION=1920x818,CLOSED-CAPTIONS=NONE,URI="v0/iframes.m3u8$q"
        |
    """.trimMargin()

    private fun rendition(dir: String, lang: String, name: String, default: Boolean, channels: Int, group: String = "audio-surround") =
        """#EXT-X-MEDIA:TYPE=AUDIO,URI="$dir/playlist.m3u8$q",GROUP-ID="$group",LANGUAGE="$lang",NAME="$name",""" +
            """DEFAULT=${if (default) "YES" else "NO"},AUTOSELECT=YES,CHANNELS="$channels""""

    @Test
    fun `on a stereo output the stereo twin of the 5_1 default is the default`() {
        assertEquals(
            master(rendition("a1", "en", "English 5.1", false, 6), rendition("a0", "en", "English", true, 2)),
            withStereoDefault(master(rendition("a1", "en", "English 5.1", true, 6), rendition("a0", "en", "English", false, 2))),
        )
        // A film without dialogue: its tracks are in zxx.
        assertEquals(
            master(rendition("a1", "zxx", "No dialogue 5.1", false, 6), rendition("a0", "zxx", "No dialogue", true, 2)),
            withStereoDefault(master(rendition("a1", "zxx", "No dialogue 5.1", true, 6), rendition("a0", "zxx", "No dialogue", false, 2))),
        )
    }

    @Test
    fun `a master without 5_1 tracks, or with a stereo default, comes back as it is`() {
        // As a TV that plays no E-AC-3 is served: the stereo group alone.
        val stereo = master(rendition("a0", "en", "English", true, 2, group = "audio"))
        assertSame(stereo, withStereoDefault(stereo))
        val stereoDefault = master(rendition("a1", "en", "English 5.1", false, 6), rendition("a0", "en", "English", true, 2))
        assertSame(stereoDefault, withStereoDefault(stereoDefault))
    }

    @Test
    fun `the twin is the next stereo track of the default's language`() {
        // Two languages: the German default goes to German, not to English.
        val german51 = rendition("a3", "de", "German 5.1", true, 6)
        val german = rendition("a2", "de", "German", false, 2)
        val english51 = rendition("a1", "en", "English 5.1", false, 6)
        val english = rendition("a0", "en", "English", false, 2)
        assertEquals(
            master(rendition("a3", "de", "German 5.1", false, 6), rendition("a2", "de", "German", true, 2), english51, english),
            withStereoDefault(master(german51, german, english51, english)),
        )
        // A commentary of the language after its twin stays as it is.
        val commentary = rendition("a4", "en", "English · Commentary", false, 2)
        assertEquals(
            master(rendition("a1", "en", "English 5.1", false, 6), rendition("a0", "en", "English", true, 2), commentary),
            withStereoDefault(master(rendition("a1", "en", "English 5.1", true, 6), english, commentary)),
        )
        // Languages compare by their ISO 639-1 code; a twin listed first is
        // still the twin when none comes after.
        assertEquals(
            master(rendition("a0", "eng", "English", true, 2), rendition("a1", "en", "English 5.1", false, 6)),
            withStereoDefault(master(rendition("a0", "eng", "English", false, 2), rendition("a1", "en", "English 5.1", true, 6))),
        )
    }

    @Test
    fun `a 5_1 default without a stereo track of its language stays the default`() {
        val m = master(rendition("a1", "fr", "French 5.1", true, 6), rendition("a0", "en", "English", false, 2))
        assertSame(m, withStereoDefault(m))
    }

    @Test
    fun `a twin without DEFAULT or AUTOSELECT gets both`() {
        val twin = """#EXT-X-MEDIA:TYPE=AUDIO,URI="a0/playlist.m3u8$q",GROUP-ID="audio-surround",LANGUAGE="en",NAME="English""""
        assertEquals(
            master(rendition("a1", "en", "English 5.1", false, 6), "$twin,DEFAULT=YES,AUTOSELECT=YES"),
            withStereoDefault(master(rendition("a1", "en", "English 5.1", true, 6), twin)),
        )
    }

    @Test
    fun `a master with CRLF line ends keeps them`() {
        val crlf = master(rendition("a1", "en", "English 5.1", true, 6), rendition("a0", "en", "English", false, 2)).replace("\n", "\r\n")
        assertEquals(
            master(rendition("a1", "en", "English 5.1", false, 6), rendition("a0", "en", "English", true, 2)).replace("\n", "\r\n"),
            withStereoDefault(crlf),
        )
    }

    @Test
    fun `CHANNELS counts its channels`() {
        assertEquals(6, channelCount("6"))
        assertEquals(2, channelCount("2"))
        // E-AC-3 with Atmos: 16 objects.
        assertEquals(16, channelCount("16/JOC"))
        assertNull(channelCount(null))
        assertNull(channelCount(""))
    }
}
