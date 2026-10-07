package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.SidecarSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestSubtitlesTest {
    private val q = "?stream=t&caps=hvc:2160,avc:2160,aac,ac3,eac3&q=auto"

    // A ladder package with HLS_SUBTITLES as chino-stream serves it to a TV
    // that decodes HEVC and E-AC-3 (its testdata 2e7c1add): one SUBTITLES
    // group, a forced English rendition in it.
    private val head = """
        |#EXTM3U
        |#EXT-X-INDEPENDENT-SEGMENTS
        |
        |#EXT-X-MEDIA:TYPE=AUDIO,URI="a0/playlist.m3u8$q",GROUP-ID="audio",LANGUAGE="en",NAME="English",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2"
        |#EXT-X-MEDIA:TYPE=AUDIO,URI="a2/playlist.m3u8$q",GROUP-ID="audio-surround",LANGUAGE="en",NAME="English 5.1",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="6"
        |
    """.trimMargin()
    private val subtitles = listOf(
        """#EXT-X-MEDIA:TYPE=SUBTITLES,URI="s0/playlist.m3u8$q",GROUP-ID="subs",LANGUAGE="en",NAME="English",DEFAULT=NO,AUTOSELECT=YES""",
        """#EXT-X-MEDIA:TYPE=SUBTITLES,URI="s1/playlist.m3u8$q",GROUP-ID="subs",LANGUAGE="en",NAME="English (Forced)",DEFAULT=NO,AUTOSELECT=YES,FORCED=YES""",
        """#EXT-X-MEDIA:TYPE=SUBTITLES,URI="s2/playlist.m3u8$q",GROUP-ID="subs",LANGUAGE="de",NAME="German",DEFAULT=NO,AUTOSELECT=YES""",
    )
    private fun variants(subs: Boolean): String {
        val s = if (subs) """,SUBTITLES="subs"""" else ""
        return """
            |
            |#EXT-X-STREAM-INF:BANDWIDTH=7436924,AVERAGE-BANDWIDTH=7380150,CODECS="hvc1.1.6.L120.90,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=23.976,VIDEO-RANGE=SDR,AUDIO="audio"$s,CLOSED-CAPTIONS=NONE
            |v0/playlist.m3u8$q
            |#EXT-X-STREAM-INF:BANDWIDTH=1390607,AVERAGE-BANDWIDTH=1370490,CODECS="hvc1.1.6.L93.90,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=23.976,VIDEO-RANGE=SDR,AUDIO="audio"$s,CLOSED-CAPTIONS=NONE
            |v1/playlist.m3u8$q
            |#EXT-X-STREAM-INF:BANDWIDTH=7693886,AVERAGE-BANDWIDTH=7636693,CODECS="hvc1.1.6.L120.90,ec-3",RESOLUTION=1920x1080,FRAME-RATE=23.976,VIDEO-RANGE=SDR,AUDIO="audio-surround"$s,CLOSED-CAPTIONS=NONE
            |v0/playlist.m3u8$q
            |
            |#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=105666,AVERAGE-BANDWIDTH=120782,CODECS="hvc1.1.6.L120.90",RESOLUTION=1920x1080,CLOSED-CAPTIONS=NONE,URI="v0/iframes.m3u8$q"
            |
        """.trimMargin()
    }

    private fun master(subs: List<String>, variantSubs: Boolean = true) =
        head + subs.joinToString("") { "$it\n" } + variants(variantSubs)

    private fun sidecar(lang: String, label: String = "", forced: Boolean = false) =
        SidecarSubtitle(id = "$lang-$label", label = label, lang = lang, url = "/api/v1/play/subs/x.vtt", forced = forced)

    // As the catalog lists the package's sidecars: ISO 639-2 codes, titles.
    private val catalogSidecars = listOf(sidecar("eng"), sidecar("eng", "Forced"), sidecar("ger"))

    @Test
    fun `renditions the sidecars have, and the forced one, are dropped, the group with them`() {
        assertEquals(
            master(emptyList(), variantSubs = false),
            withoutDuplicateSubtitles(master(subtitles), catalogSidecars),
        )
    }

    @Test
    fun `without the sidecar list the master's are the subtitles, the forced one among them`() {
        val m = master(subtitles)
        assertSame(m, withoutDuplicateSubtitles(m, emptyList()))
    }

    @Test
    fun `a language or a kind the sidecars lack keeps its rendition`() {
        // A regular English sidecar: the forced English rendition stays.
        assertEquals(
            master(listOf(subtitles[1], subtitles[2])),
            withoutDuplicateSubtitles(master(subtitles), listOf(sidecar("en"))),
        )
    }

    @Test
    fun `a master without SUBTITLES comes back as it is`() {
        val plain = master(emptyList(), variantSubs = false)
        assertSame(plain, withoutDuplicateSubtitles(plain, catalogSidecars))
    }

    @Test
    fun `SDH is a kind of its own`() {
        val sdh = """#EXT-X-MEDIA:TYPE=SUBTITLES,URI="s3/playlist.m3u8",GROUP-ID="subs",LANGUAGE="en",NAME="English (SDH)",DEFAULT=NO,AUTOSELECT=YES"""
        assertTrue(keepRendition("en", "English (SDH)", false, null, listOf(sidecar("eng"))))
        assertFalse(keepRendition("en", "English (SDH)", false, null, listOf(sidecar("eng"), sidecar("eng", "SDH"))))
        assertFalse(
            keepRendition(
                "en", "English", false,
                "public.accessibility.transcribes-spoken-dialog,public.accessibility.describes-music-and-sound",
                listOf(sidecar("en", "SDH")),
            ),
        )
        assertEquals(
            master(listOf(sdh)),
            withoutDuplicateSubtitles(master(subtitles + sdh), catalogSidecars),
        )
    }

    @Test
    fun `a forced rendition stays where no sidecar of its language is forced`() {
        assertTrue(keepRendition("de", "German (forced)", true, null, emptyList()))
        assertTrue(keepRendition("de", "German (forced)", true, null, listOf(sidecar("ger"))))
        assertFalse(keepRendition("de", "German (forced)", true, null, listOf(sidecar("ger", "Forced"))))
        // FORCED read past a quoted value holding a comma.
        val signs = """#EXT-X-MEDIA:TYPE=SUBTITLES,URI="s4/playlist.m3u8",GROUP-ID="subs",NAME="Signs, Songs",LANGUAGE="fr",FORCED=YES"""
        assertEquals(
            master(listOf(subtitles[2])),
            withoutDuplicateSubtitles(master(listOf(subtitles[2], signs)), listOf(sidecar("fre", "Forced"))),
        )
        val kept = master(listOf(subtitles[2], signs))
        assertSame(kept, withoutDuplicateSubtitles(kept, listOf(sidecar("fre"))))
    }

    @Test
    fun `a sidecar is forced where chino-api says so, by its label where it says of none`() {
        // chino-api says which are forced, and leaves the field out of the
        // others: a label saying "forced" makes no other one so.
        assertEquals(
            listOf(SubtitleKind.REGULAR, SubtitleKind.FORCED, SubtitleKind.REGULAR, SubtitleKind.SDH),
            sidecarKinds(listOf(sidecar("eng"), sidecar("eng", forced = true), sidecar("ger", "Forced"), sidecar("ger", "SDH"))),
        )
        // An older chino-api says it of none, as does a title without a
        // forced track: the labels say it.
        assertEquals(
            listOf(SubtitleKind.REGULAR, SubtitleKind.FORCED, SubtitleKind.SDH),
            sidecarKinds(listOf(sidecar("eng"), sidecar("eng", "Forced"), sidecar("eng", "SDH"))),
        )
        // The forced rendition goes where chino-api names a forced sidecar of
        // its language, unlabelled as the catalog's sidecars mostly are.
        assertFalse(keepRendition("en", "English (Forced)", true, null, listOf(sidecar("eng"), sidecar("eng", forced = true))))
        assertTrue(keepRendition("de", "German (forced)", true, null, listOf(sidecar("ger", "Forced"), sidecar("eng", forced = true))))
        assertEquals(
            master(listOf(subtitles[2])),
            withoutDuplicateSubtitles(master(subtitles), listOf(sidecar("eng"), sidecar("eng", forced = true))),
        )
    }

    @Test
    fun `a master with CRLF line ends is filtered alike`() {
        val crlf = master(subtitles).replace("\n", "\r\n")
        assertEquals(
            master(emptyList(), variantSubs = false).replace("\n", "\r\n"),
            withoutDuplicateSubtitles(crlf, catalogSidecars),
        )
    }

    @Test
    fun `languages compare by their ISO 639-1 code`() {
        assertEquals("en", languageKey("eng"))
        assertEquals("en", languageKey("en-US"))
        assertEquals("de", languageKey("ger"))
        assertEquals("de", languageKey("deu"))
        assertEquals("de", languageKey("DE_ch"))
        assertEquals("zh", languageKey("chi"))
        assertEquals("pt", languageKey("pt-BR"))
        assertNull(languageKey("und"))
        assertNull(languageKey(""))
        assertNull(languageKey(null))
    }
}
