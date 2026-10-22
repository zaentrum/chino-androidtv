package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.InfoTrack
import cloud.nalet.chino.tv.data.api.SidecarSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subtitles of a playback: which tracks the player side-loads, the
 * menu's order ("off", the viewer's language, the server's, "more
 * languages"), duplicates once, and what comes on by itself.
 */
class SubtitleChoicesTest {
    // A title played from its original file, as /play/info lists its
    // subtitle streams: 42 SubRip tracks, chino-stream's names.
    private val fileStreams = listOf(
        "eng" to "English", "eng" to "English · SDH", "ara" to "Arabic", "bul" to "bul",
        "chi" to "Chinese · Simplified", "chi" to "Chinese · Traditional", "cze" to "Czech", "dan" to "Danish",
        "ger" to "German", "gre" to "Greek", "spa" to "Spanish · Latin American", "spa" to "Spanish",
        "est" to "est", "fin" to "Finnish", "fre" to "French · Canadian", "fre" to "French", "heb" to "Hebrew",
        "hin" to "Hindi", "hun" to "Hungarian", "ind" to "ind", "ita" to "Italian", "jpn" to "Japanese",
        "kor" to "Korean", "lit" to "lit", "lav" to "lav", "may" to "may", "dut" to "Dutch", "nor" to "Norwegian",
        "pol" to "Polish", "por" to "Portuguese", "por" to "Portuguese · Brazilian", "rus" to "Russian",
        "slo" to "slo", "slv" to "slv", "swe" to "Swedish", "tam" to "tam", "tel" to "tel", "tha" to "Thai",
        "tur" to "Turkish", "ukr" to "Ukrainian", "vie" to "Vietnamese", "chi" to "Chinese · Traditional",
    ).mapIndexed { i, (lang, name) -> InfoTrack(index = i, codec = "subrip", language = lang, name = name, title = name) }

    // Three subtitles attached to it, made for the server's languages.
    private val attached = listOf("de", "en", "zh-Hans").map { lang ->
        SidecarSubtitle(id = "ai-$lang", label = "AI", lang = lang, url = "https://media.example.org/api/v1/play/subs/ai-$lang.vtt?stream=t", format = "webvtt")
    }

    private val sources = sideLoadedSubtitles(attached, fileStreams) { i -> "https://media.example.org/api/v1/items/x1/play/subtitles/$i.vtt?stream=t" }

    /** The tracks as Media3 lists them: the side-loaded ones in their order. */
    private val tracks = sources.map { SubtitleTrackInfo(it.id, it.language, it.label, it.forced, it.sdh) }

    private val server = listOf("en", "de", "zh-Hans")

    private fun labels(entries: List<SubtitleEntry>) = entries.map { it.label }

    @Test
    fun `every subtitle is side-loaded, the sidecars first, each stream at its extraction`() {
        assertEquals(45, sources.size)
        assertEquals(listOf("side:ai-de", "side:ai-en", "side:ai-zh-Hans", "emb:0", "emb:1"), sources.take(5).map { it.id })
        val sdh = sources.single { it.id == "emb:1" }
        assertEquals("https://media.example.org/api/v1/items/x1/play/subtitles/1.vtt?stream=t", sdh.url)
        assertEquals("text/vtt", sdh.mimeType)
        assertEquals("eng", sdh.language)
        assertEquals("English · SDH", sdh.label)
        assertTrue(sdh.sdh)
        assertFalse(sdh.forced)
        assertEquals(attached[0].url, sources[0].url)
        assertEquals("AI", sources[0].label)
    }

    @Test
    fun `a picture stream, one without an index and a forced one`() {
        val rows = listOf(
            InfoTrack(index = 3, codec = "hdmv_pgs_subtitle", language = "fre"),
            // A packaged title's row: no stream index, its sidecar is the subtitle.
            InfoTrack(index = null, codec = "webvtt", language = "eng"),
            InfoTrack(index = 5, codec = "subrip", language = "eng", name = "English (forced)", forced = true),
            InfoTrack(index = 6, codec = "ass", language = "ger", name = "German · Forced"),
        )
        val loaded = sideLoadedSubtitles(emptyList(), rows) { "u$it" }
        assertEquals(listOf("emb:5", "emb:6"), loaded.map { it.id })
        assertEquals(listOf(true, true), loaded.map { it.forced })
        // A sidecar's format picks its decoder; a forced one says so.
        val pgs = sideLoadedSubtitles(
            listOf(
                SidecarSubtitle(id = "p", lang = "eng", url = "u", format = "pgs"),
                SidecarSubtitle(id = "f", lang = "eng", url = "u", format = "srt", forced = true),
            ),
            emptyList(),
        ) { "u$it" }
        assertEquals(listOf("application/pgs", "application/x-subrip"), pgs.map { it.mimeType })
        assertEquals(listOf(false, true), pgs.map { it.forced })
    }

    @Test
    fun `the menu id of a side-loaded track, under the prefix a merged source gives it`() {
        assertEquals("emb:4", sideLoadedId("emb:4"))
        assertEquals("emb:4", sideLoadedId("3:emb:4"))
        assertEquals("side:ai-zh-Hans", sideLoadedId("1:side:ai-zh-Hans"))
        assertEquals("side:x", sideLoadedId("0:1:side:x"))
        assertNull(sideLoadedId("0:subs:English"))
        assertNull(sideLoadedId(null))
    }

    @Test
    fun `the server's languages first, each the file's own before the attached, then more languages`() {
        val menu = subtitleMenu(tracks, preferred = "off", serverLanguages = server)
        assertEquals(
            listOf(
                "English", "English · AI", "English · for deaf and hard of hearing",
                "German", "German · AI",
                "Chinese (Simplified)", "Chinese (Simplified) · AI",
            ),
            labels(menu.top),
        )
        assertEquals(listOf("emb:0", "side:ai-en", "emb:1", "emb:8", "side:ai-de", "emb:4", "side:ai-zh-Hans"), menu.top.map { it.id })
        assertEquals(
            listOf(
                "Arabic", "Bulgarian", "Chinese (Traditional)", "Czech", "Danish", "Dutch", "Estonian", "Finnish",
                "French", "French (Canada)", "Greek", "Hebrew", "Hindi", "Hungarian", "Indonesian", "Italian",
                "Japanese", "Korean", "Latvian", "Lithuanian", "Malay", "Norwegian", "Polish", "Portuguese",
                "Portuguese (Brazil)", "Russian", "Slovak", "Slovenian", "Spanish", "Spanish (Latin America)",
                "Swedish", "Tamil", "Telugu", "Thai", "Turkish", "Ukrainian", "Vietnamese",
            ),
            labels(menu.more),
        )
        // Every track is in the menu once: 45 tracks, one of them twice in
        // the file (the second Traditional Chinese), 44 entries.
        assertEquals(44, menu.all.size)
        assertEquals(menu.all.size, menu.all.map { it.label }.toSet().size)
        assertEquals("emb:5", menu.more.single { it.label == "Chinese (Traditional)" }.id)
    }

    @Test
    fun `the viewer's own language comes first, the server's after it`() {
        val german = subtitleMenu(tracks, preferred = "de", serverLanguages = server)
        assertEquals(
            listOf("German", "German · AI", "English", "English · AI", "English · for deaf and hard of hearing", "Chinese (Simplified)", "Chinese (Simplified) · AI"),
            labels(german.top),
        )
        // A language the server is not set up for: first, its variants after it.
        val french = subtitleMenu(tracks, preferred = "fr", serverLanguages = server)
        assertEquals(listOf("French", "French (Canada)", "English"), labels(french.top).take(3))
        assertFalse("French" in labels(french.more))
        assertTrue("German" in labels(french.top))
    }

    @Test
    fun `an older server names no languages - the viewer's then everything under more languages`() {
        val noServer = subtitleMenu(tracks, preferred = "off", serverLanguages = emptyList())
        assertEquals(emptyList<String>(), labels(noServer.top))
        assertEquals(44, noServer.more.size)
        assertEquals(listOf("Arabic", "Bulgarian", "Chinese (Simplified)", "Chinese (Simplified) · AI", "Chinese (Traditional)"), labels(noServer.more).take(5))
        assertEquals(listOf("English", "English · AI", "English · for deaf and hard of hearing"), labels(noServer.more).filter { it.startsWith("English") })
        val italian = subtitleMenu(tracks, preferred = "it", serverLanguages = emptyList())
        assertEquals(listOf("Italian"), labels(italian.top))
        assertEquals(43, italian.more.size)
    }

    @Test
    fun `a title with the server's languages only has nothing more`() {
        val packaged = listOf(
            SubtitleTrackInfo("side:1", "eng"),
            SubtitleTrackInfo("side:2", "eng", "Forced", forced = true),
            SubtitleTrackInfo("side:3", "ger"),
            SubtitleTrackInfo("side:4", "eng", "SDH"),
        )
        val menu = subtitleMenu(packaged, preferred = "off", serverLanguages = listOf("en", "de"))
        assertEquals(
            listOf("English", "English · for deaf and hard of hearing", "English · foreign-language parts only", "German"),
            labels(menu.top),
        )
        assertTrue(menu.more.isEmpty())
    }

    @Test
    fun `within a language plain, for deaf and hard of hearing, foreign-language parts only, a duplicate once`() {
        val messy = listOf(
            SubtitleTrackInfo("a", "eng", "Forced", forced = true),
            SubtitleTrackInfo("b", "eng", "SDH"),
            SubtitleTrackInfo("c", "eng", "English"),
            SubtitleTrackInfo("d", "eng"),
            SubtitleTrackInfo("e", "eng", "English (2)"),
            SubtitleTrackInfo("f", "eng", "Forced, SDH", forced = true),
        )
        val menu = subtitleMenu(messy, preferred = "en", serverLanguages = emptyList())
        assertEquals(
            listOf(
                "English",
                "English · for deaf and hard of hearing",
                "English · foreign-language parts only",
                "English · foreign-language parts only · for deaf and hard of hearing",
            ),
            labels(menu.top),
        )
        // The first of the three plain ones in the player's order.
        assertEquals("c", menu.top.first().id)
    }

    @Test
    fun `Simplified and Traditional Chinese are two languages, an unmarked one counts for either`() {
        assertEquals(0, languageMatch("zh-Hans", "zh-Hans"))
        assertNull(languageMatch("zh-Hans", "zh-Hant"))
        assertEquals(1, languageMatch("zh-Hans", "zh"))
        assertEquals(0, languageMatch("zh-Hant", "zh-Hant-TW"))
        // A region that writes one script is that script.
        assertEquals(0, languageMatch("zh-Hans", "zh-CN"))
        assertNull(languageMatch("zh-Hant", "zh-CN"))
        assertEquals(0, languageMatch("en", "en"))
        assertEquals(1, languageMatch("en", "en-GB"))
        assertNull(languageMatch("pt-BR", "pt-PT"))
        assertEquals(1, languageMatch("pt-BR", "pt"))
        assertNull(languageMatch("de", "en"))
        assertNull(languageMatch("en", ""))
        assertTrue(sameLanguage("", ""))
        assertFalse(sameLanguage("", "en"))
        val chinese = listOf(
            SubtitleTrackInfo("emb:5", "chi", "Chinese · Traditional"),
            SubtitleTrackInfo("emb:9", "chi", "Chinese"),
            SubtitleTrackInfo("emb:4", "chi", "Chinese · Simplified"),
        )
        val menu = subtitleMenu(chinese, preferred = "off", serverLanguages = listOf("zh-Hans"))
        // The very one first, then the one that may be it.
        assertEquals(listOf("Chinese (Simplified)", "Chinese"), labels(menu.top))
        assertEquals(listOf("Chinese (Traditional)"), labels(menu.more))
    }

    // What comes on by itself.

    private val e06 = subtitleMenu(tracks, preferred = "off", serverLanguages = server)

    @Test
    fun `off by default, never a track because it comes first or a file flags it default`() {
        assertNull(autoSubtitle(e06, audioLang = "eng", preferred = "off"))
        assertNull(autoSubtitle(e06, audioLang = "eng", preferred = ""))
        assertNull(autoSubtitle(e06, audioLang = "eng", preferred = null))
        // A language the title lacks: nothing in another language instead.
        assertNull(autoSubtitle(subtitleMenu(tracks, "is", server), audioLang = "eng", preferred = "is"))
    }

    @Test
    fun `the preferred subtitle language where the title has it, its plain track`() {
        assertEquals("emb:8", autoSubtitle(subtitleMenu(tracks, "de", server), audioLang = "eng", preferred = "de")?.id)
        assertEquals("emb:0", autoSubtitle(subtitleMenu(tracks, "en", server), audioLang = "eng", preferred = "en")?.id)
        // One "more languages" holds: still the viewer's language.
        assertEquals("emb:20", autoSubtitle(subtitleMenu(tracks, "it", server), audioLang = "eng", preferred = "it")?.id)
        // Tagged the other way round: one language.
        assertEquals("emb:8", autoSubtitle(subtitleMenu(tracks, "deu", server), audioLang = "eng", preferred = "deu")?.id)
    }

    // A film in English with a German dub, as the menu lists its subtitles:
    // the sidecars in the catalog's ISO 639-2 codes, a forced one of each
    // language, the English one also as PGS, listed before its text twin.
    private val dubbed = listOf(
        SubtitleTrackInfo("side:de", "ger"),
        SubtitleTrackInfo("side:en-pgs", "eng", forced = true, image = true),
        SubtitleTrackInfo("side:en-forced", "eng", forced = true),
        SubtitleTrackInfo("side:en", "eng"),
        SubtitleTrackInfo("side:de-forced", "ger", forced = true),
    )
    private val dubbedMenu = subtitleMenu(dubbed, preferred = "off", serverLanguages = listOf("en", "de"))

    @Test
    fun `where nothing else comes on, the foreign-language parts only of the audio's language, text before picture`() {
        assertEquals("side:en-forced", autoSubtitle(dubbedMenu, audioLang = "en", preferred = "off")?.id)
        assertEquals("side:de-forced", autoSubtitle(dubbedMenu, audioLang = "deu", preferred = "off")?.id)
        assertEquals("side:en-forced", autoSubtitle(dubbedMenu, audioLang = "eng", preferred = "fr")?.id)
        // Only the picture one: it.
        val pictureOnly = subtitleMenu(dubbed.filter { it.id != "side:en-forced" }, "off", listOf("en", "de"))
        assertEquals("side:en-pgs", autoSubtitle(pictureOnly, audioLang = "en", preferred = "off")?.id)
        // No forced track in the audio's language, or no language known: none.
        assertNull(autoSubtitle(dubbedMenu, audioLang = "fr", preferred = "off"))
        for (audio in listOf("und", "zxx", "", null)) assertNull(audio.toString(), autoSubtitle(dubbedMenu, audio, "off"))
        // The preferred language before any forced track.
        assertEquals("side:de", autoSubtitle(dubbedMenu, audioLang = "en", preferred = "de")?.id)
    }

    @Test
    fun `a subtitle as text and as pictures is one entry, the text one`() {
        assertEquals(
            listOf("English", "English · foreign-language parts only", "German", "German · foreign-language parts only"),
            labels(dubbedMenu.top),
        )
        assertEquals("side:en-forced", dubbedMenu.top[1].id)
        // Pictures alone stay.
        assertEquals("side:p", subtitleMenu(listOf(SubtitleTrackInfo("side:p", "eng", image = true)), "off", listOf("en")).top.single().id)
    }

    @Test
    fun `a preferred language with only its foreign-language parts takes them`() {
        val forcedOnly = subtitleMenu(listOf(SubtitleTrackInfo("side:de-forced", "ger", forced = true)), "de", emptyList())
        assertEquals("side:de-forced", autoSubtitle(forcedOnly, audioLang = "eng", preferred = "de")?.id)
    }

    @Test
    fun `the player decides at the start and as the audio's language changes until the viewer picks`() {
        val session = SubtitleSession()
        assertTrue(session.playerDecides("eng"))
        session.playerChose("side:en-forced")
        assertEquals("side:en-forced", session.chosen)
        // English 5.1 to English, "eng" to "en": the same language, no change.
        assertFalse(session.playerDecides("en"))
        assertFalse(session.playerDecides("eng"))
        // The German dub: decided again, and the forced track goes with it.
        assertTrue(session.playerDecides("ger"))
        session.playerChose(autoSubtitle(dubbedMenu, "ger", "off")?.id)
        assertEquals("side:de-forced", session.chosen)
        // A language without one: off.
        assertTrue(session.playerDecides("fre"))
        session.playerChose(autoSubtitle(dubbedMenu, "fre", "off")?.id)
        assertNull(session.chosen)
        // The viewer's Off (or a track): theirs for the rest of the playback.
        session.viewerPicks(null)
        assertTrue(session.viewerPicked)
        assertFalse(session.playerDecides("eng"))
        session.playerChose("side:en-forced")
        assertNull(session.chosen)
        session.viewerPicks("side:en")
        assertEquals("side:en", session.chosen)
        assertFalse(session.playerDecides("ger"))
    }

    @Test
    fun `an audio of no known language is one language too`() {
        val session = SubtitleSession()
        assertTrue(session.playerDecides(null))
        assertFalse(session.playerDecides("und"))
        assertTrue(session.playerDecides("fre"))
    }

    @Test
    fun `an image subtitle is told by its format, a parsed one by its codecs`() {
        assertTrue(isImageSubtitle("application/pgs", null))
        assertTrue(isImageSubtitle("application/vobsub", null))
        assertTrue(isImageSubtitle("application/dvbsubs", null))
        assertTrue(isImageSubtitle("application/x-media3-cues", "application/pgs"))
        assertFalse(isImageSubtitle("application/x-media3-cues", "text/vtt"))
        assertFalse(isImageSubtitle("text/vtt", null))
        assertFalse(isImageSubtitle("application/x-subrip", null))
        assertFalse(isImageSubtitle(null, null))
    }

    @Test
    fun `asking for subtitles is a language, not off`() {
        assertTrue(asksForSubtitles("en"))
        assertTrue(asksForSubtitles("deu"))
        for (pref in listOf("off", "OFF", " ", "", null, "und")) assertFalse(pref.toString(), asksForSubtitles(pref))
    }
}
