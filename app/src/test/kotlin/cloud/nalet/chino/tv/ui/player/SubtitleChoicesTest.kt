package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.InfoTrack
import cloud.nalet.chino.tv.data.api.SidecarSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
    fun `the server's languages first, each the file's own before the platform's, then more languages`() {
        val menu = subtitleMenu(tracks, preferred = "off", serverLanguages = server)
        assertEquals(
            listOf(
                "English", "English · for deaf and hard of hearing", "English · AI",
                "German", "German · AI",
                "Chinese (Simplified)", "Chinese (Simplified) · AI",
            ),
            labels(menu.top),
        )
        assertEquals(listOf("emb:0", "emb:1", "side:ai-en", "emb:8", "side:ai-de", "emb:4", "side:ai-zh-Hans"), menu.top.map { it.id })
        // The rest in alphabetical order of their languages' names, a
        // language's tracks together, the bare name before its regions.
        // chino-stream's "Spanish · Latin American" is the region, as the
        // web reads it (TrackLabelsTest).
        assertEquals(
            listOf(
                "Arabic", "Bulgarian", "Chinese (Traditional)", "Czech", "Danish", "Dutch", "Estonian", "Finnish",
                "French", "French (Canada)", "Greek", "Hebrew", "Hindi", "Hungarian", "Indonesian", "Italian",
                "Japanese", "Korean", "Latvian", "Lithuanian", "Malay", "Norwegian", "Polish", "Portuguese",
                "Portuguese (Brazil)", "Russian", "Slovak", "Slovenian", "Spanish", "Spanish (Latin America)", "Swedish",
                "Tamil", "Telugu", "Thai", "Turkish", "Ukrainian", "Vietnamese",
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
            listOf("German", "German · AI", "English", "English · for deaf and hard of hearing", "English · AI", "Chinese (Simplified)", "Chinese (Simplified) · AI"),
            labels(german.top),
        )
        // A language the server is not set up for: first, the bare name
        // before its region.
        val french = subtitleMenu(tracks, preferred = "fr", serverLanguages = server)
        assertEquals(listOf("French", "French (Canada)", "English"), labels(french.top).take(3))
        assertFalse("French" in labels(french.more))
        assertTrue("German" in labels(french.top))
    }

    @Test
    fun `a server that names no languages - the viewer's own languages on top, every other under more languages`() {
        // An older server (its 404), or one with no subtitle languages set:
        // the subtitle setting's language, the audio setting's (French) and
        // the TV's (English); the other 34 languages behind "more languages".
        for (none in listOf(emptyList(), listOf(""))) {
            val menu = subtitleMenu(tracks, preferred = "it", serverLanguages = none, own = ownLanguages("fr", "en"))
            assertEquals(
                listOf(
                    "Italian",
                    "French", "French (Canada)",
                    "English", "English · for deaf and hard of hearing", "English · AI",
                ),
                labels(menu.top),
            )
            assertEquals(38, menu.more.size)
            assertEquals(listOf("Arabic", "Bulgarian", "Chinese (Simplified)", "Chinese (Simplified) · AI", "Chinese (Traditional)"), labels(menu.more).take(5))
            assertEquals(44, menu.all.size)
        }
        // Original audio names no language; the TV's alone then.
        val original = subtitleMenu(tracks, preferred = "off", serverLanguages = emptyList(), own = ownLanguages("orig", "de"))
        assertEquals(listOf("German", "German · AI"), labels(original.top))
        // Nothing of the viewer's at all: "off" and "more languages", never
        // every track on top.
        val nothing = subtitleMenu(tracks, preferred = "off", serverLanguages = emptyList())
        assertTrue(nothing.top.isEmpty())
        assertEquals(44, nothing.more.size)
    }

    @Test
    fun `a hundred tracks - the viewer's three languages on top, the other ninety-odd behind more languages`() {
        // The owner's case: a file with a hundred subtitles, a viewer who
        // reads German, English and Chinese.
        val others = Locale.getISOLanguages().map(::languageTag)
            .filter { it.isNotEmpty() && it !in setOf("de", "en", "zh") && languageName(it) != it }
            .distinctBy { languageName(it) }
            .take(97)
        val codes = others.take(40) + "de" + others.subList(40, 70) + "en" + others.drop(70) + "zh"
        val hundred = codes.mapIndexed { i, code -> SubtitleTrackInfo("emb:$i", code) }
        assertEquals(100, hundred.size)
        val menu = subtitleMenu(hundred, preferred = "de", serverLanguages = emptyList(), own = ownLanguages("en", "zh-Hans"))
        assertEquals(listOf("German", "English", "Chinese"), labels(menu.top))
        assertEquals(97, menu.more.size)
        val names = labels(menu.more)
        assertEquals(names.sortedWith(java.text.Collator.getInstance(Locale.ENGLISH)), names)
    }

    @Test
    fun `the viewer's own languages - the audio setting's unless Original, then the TV's, its script without its region`() {
        assertEquals(listOf("fr", "de"), ownLanguages("fr", "de"))
        assertEquals(listOf("de"), ownLanguages("orig", "de"))
        assertEquals(listOf("de"), ownLanguages("", "de"))
        assertEquals(listOf("pt-BR"), ownLanguages("pt_br", null))
        assertEquals("en", deviceLanguage(Locale.US))
        assertEquals("de", deviceLanguage(Locale.forLanguageTag("de-CH")))
        assertEquals("zh-Hans", deviceLanguage(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-Hant", deviceLanguage(Locale.TRADITIONAL_CHINESE))
        assertEquals("zh-Hant", deviceLanguage(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals("", deviceLanguage(Locale.ROOT))
    }

    @Test
    fun `a packaged title follows the same rule, what its source brought behind more languages`() {
        // A package keeps what its source brought besides the server's
        // languages: its renditions, as Media3 lists the master's.
        val packaged = listOf(
            SubtitleTrackInfo("0:subs#0", "fre", "French"),
            SubtitleTrackInfo("0:subs#1", "eng", "English"),
            SubtitleTrackInfo("0:subs#2", "ita", "Italian"),
            SubtitleTrackInfo("0:subs#3", "ger", "German"),
        )
        val menu = subtitleMenu(packaged, preferred = "off", serverLanguages = listOf("en", "de"))
        assertEquals(listOf("English", "German"), labels(menu.top))
        assertEquals(listOf("French", "Italian"), labels(menu.more))
        // A server that names none: the viewer's own on top, the rest behind
        // "more languages" all the same.
        val own = subtitleMenu(packaged, "off", emptyList(), own = listOf("it"))
        assertEquals(listOf("Italian"), labels(own.top))
        assertEquals(listOf("English", "French", "German"), labels(own.more))
    }

    @Test
    fun `Mandarin and Cantonese are two, never one`() {
        val hk = listOf(SubtitleTrackInfo("emb:2", "chi", "國語"), SubtitleTrackInfo("emb:3", "chi", "粵語"), SubtitleTrackInfo("emb:4", "chi", "中文"))
        assertEquals(listOf("Chinese · Mandarin", "Chinese · Cantonese", "Chinese"), labels(subtitleMenu(hk, "off", emptyList(), own = listOf("zh-Hant")).top))
        assertEquals(listOf("Chinese · Mandarin", "Chinese · Cantonese", "Chinese"), labels(subtitleMenu(hk, "off", listOf("en")).more))
    }

    @Test
    fun `tracks that read the same show once, never numbered`() {
        val twice = listOf(
            SubtitleTrackInfo("side:en", "en"),
            SubtitleTrackInfo("emb:6", "eng"),
            SubtitleTrackInfo("emb:7", "eng", "English (2)"),
            SubtitleTrackInfo("emb:8", "eng", "Commentary"),
        )
        val menu = subtitleMenu(twice, "en", listOf("en"))
        // The file's own before the one the server attached, wherever the
        // player lists them.
        assertEquals(listOf("emb:6", "emb:8"), menu.top.map { it.id })
        assertEquals(listOf("English", "English · Commentary"), labels(menu.top))
        assertTrue(menu.all.none { it.label.endsWith("(2)") })
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
    fun `a track titled in its own language is the plain one, shown once, the file's own first`() {
        val german = listOf(
            SubtitleTrackInfo("emb:3", "ger", "Deutsch"),
            SubtitleTrackInfo("side:de", "de", "German"),
            SubtitleTrackInfo("emb:5", "ger", "Deutsch (Forced)", forced = true),
        )
        val menu = subtitleMenu(german, preferred = "de", serverLanguages = listOf("de"))
        assertEquals(listOf("German", "German · foreign-language parts only"), labels(menu.top))
        assertEquals(listOf("emb:3", "emb:5"), menu.top.map { it.id })
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
        // The bare name first, then the script's own run.
        assertEquals(listOf("Chinese", "Chinese (Simplified)"), labels(menu.top))
        assertEquals(listOf("Chinese (Traditional)"), labels(menu.more))
    }

    @Test
    fun `within a language the bare name first, each region or script a run of its own in alphabetical order`() {
        // The spec's own example: "Chinese", "Chinese (Simplified)",
        // "Chinese (Simplified) · AI", "Chinese (Traditional)".
        val chinese = listOf(
            SubtitleTrackInfo("emb:2", "zh-Hant"),
            SubtitleTrackInfo("emb:3", "chi", "中文"),
            SubtitleTrackInfo("emb:4", "zh-Hans"),
            SubtitleTrackInfo("side:ai-zh-Hans", "zh-Hans", "AI"),
        )
        for (server in listOf(emptyList(), listOf("en"))) {
            assertEquals(
                listOf("Chinese", "Chinese (Simplified)", "Chinese (Simplified) · AI", "Chinese (Traditional)"),
                labels(subtitleMenu(chinese, "off", server).all),
            )
        }
        // The server's zh-Hans, the file's unmarked Chinese and the platform's
        // AI one: the file's first, so a zh-Hans viewer hearing English gets
        // the file's own, not the AI one.
        val e06 = listOf(SubtitleTrackInfo("emb:3", "chi", "Chinese"), SubtitleTrackInfo("side:ai-zh-Hans", "zh-Hans", "AI"))
        for ((server, preferred) in listOf(listOf("en", "de", "zh-Hans") to "off", emptyList<String>() to "zh-Hans")) {
            val menu = subtitleMenu(e06, preferred, server)
            assertEquals(listOf("Chinese", "Chinese (Simplified) · AI"), labels(menu.top))
            assertEquals("emb:3", autoSubtitle(subtitleMenu(e06, "zh-Hans", server), audioLang = "eng", preferred = "zh-Hans")?.id)
        }
        // A plain track of a region after a plain and an SDH one of the bare
        // language: each run by its kinds.
        val english = listOf(
            SubtitleTrackInfo("emb:2", "en-GB"),
            SubtitleTrackInfo("emb:3", "eng", "SDH"),
            SubtitleTrackInfo("emb:4", "eng"),
        )
        assertEquals(
            listOf("English", "English · for deaf and hard of hearing", "English (United Kingdom)"),
            labels(subtitleMenu(english, "en", listOf("en")).top),
        )
    }

    @Test
    fun `a language named twice on top, by two scripts or regions, is one block where it first comes - never split by the languages between`() {
        // chino-web's subtitles.test.ts: the file's Chinese and Traditional
        // Chinese, English and German, the platform's Simplified and English.
        val tracks = listOf(
            SubtitleTrackInfo("emb:2", "chi"),
            SubtitleTrackInfo("emb:3", "chi", "繁體中文"),
            SubtitleTrackInfo("emb:4", "eng"),
            SubtitleTrackInfo("emb:5", "ger"),
            SubtitleTrackInfo("side:zh", "zh-Hans", "AI"),
            SubtitleTrackInfo("side:en", "en", "AI"),
        )
        val chineseFirst = listOf("Chinese", "Chinese (Simplified) · AI", "Chinese (Traditional)", "English", "English · AI", "German")
        // The setting's Traditional Chinese, the server's Simplified after
        // English and German: Chinese together, first, its runs in their order.
        val menu = subtitleMenu(tracks, "zh-Hant", listOf("en", "de", "zh-Hans"))
        assertEquals(chineseFirst, labels(menu.top))
        assertTrue(menu.more.isEmpty())
        // The same where the setting is one of the server's languages.
        assertEquals(chineseFirst, labels(subtitleMenu(tracks, "zh-Hant", listOf("en", "de", "zh-Hans", "zh-Hant")).top))
        // The server's list naming Chinese twice, German between: Chinese
        // where it first comes.
        assertEquals(
            listOf("English", "English · AI", "Chinese", "Chinese (Simplified) · AI", "Chinese (Traditional)", "German"),
            labels(subtitleMenu(tracks, "en", listOf("en", "zh-Hans", "de", "zh-Hant")).top),
        )
        // A region the same: the setting's Portugal, the server's Brazil
        // after English - Portuguese together, the bare name first.
        val portuguese = listOf(
            SubtitleTrackInfo("subs#0", "por", "Portuguese (Brazil)"),
            SubtitleTrackInfo("subs#1", "por", "Portuguese"),
            SubtitleTrackInfo("subs#2", "eng", "English"),
        )
        assertEquals(
            listOf("Portuguese", "Portuguese (Brazil)", "English"),
            labels(subtitleMenu(portuguese, "pt-PT", listOf("en", "pt-BR")).top),
        )
        // Which tracks a server language takes is still its script's: the
        // server's Simplified alone leaves Traditional under more languages.
        val simplified = subtitleMenu(tracks, "en", listOf("en", "zh-Hans"))
        assertEquals(listOf("English", "English · AI", "Chinese", "Chinese (Simplified) · AI"), labels(simplified.top))
        assertEquals(listOf("Chinese (Traditional)", "German"), labels(simplified.more))
    }

    // chino-web's subtitles.test.ts: the menu without the server's languages.
    private val tenTracks = listOf(
        SubtitleTrackInfo("emb:2", "chi", "中文"),
        SubtitleTrackInfo("emb:3", "fre"),
        SubtitleTrackInfo("emb:4", "und", "Signs"),
        SubtitleTrackInfo("emb:5", "ger"),
        SubtitleTrackInfo("emb:6", "fre", "SDH"),
        SubtitleTrackInfo("emb:7", "eng"),
        SubtitleTrackInfo("emb:8", "zh-Hant"),
        SubtitleTrackInfo("side:zh", "zh-Hans", "AI"),
        SubtitleTrackInfo("side:de", "de", "AI"),
        SubtitleTrackInfo("side:en", "en", "AI"),
    )

    @Test
    fun `no server languages, the viewer's languages on top, the rest in alphabetical order, a track in no language by its own`() {
        val menu = subtitleMenu(tenTracks, "eng", emptyList(), own = ownLanguages("orig", "de"))
        assertEquals(listOf("English", "English · AI", "German", "German · AI"), labels(menu.top))
        assertEquals(
            listOf(
                "Chinese", "Chinese (Simplified) · AI", "Chinese (Traditional)",
                "French", "French · for deaf and hard of hearing",
                // A track in no language: by its own name.
                "Signs",
            ),
            labels(menu.more),
        )
        // Subtitles off in Settings and nothing else of the viewer's: all
        // behind "more languages".
        assertTrue(subtitleMenu(tenTracks, "off", emptyList()).top.isEmpty())
    }

    @Test
    fun `no server languages, a preferred region or script puts its whole language on top, the bare name then the runs alphabetically`() {
        // A preferred language with a script: the whole language, its runs
        // in their order.
        val zhHans = subtitleMenu(tenTracks, "zh-Hans", emptyList())
        assertEquals(listOf("Chinese", "Chinese (Simplified) · AI", "Chinese (Traditional)"), labels(zhHans.top))
        assertEquals("English", labels(zhHans.more).first())
        val zhHant = listOf(SubtitleTrackInfo("emb:2", "chi"), SubtitleTrackInfo("emb:3", "eng"), SubtitleTrackInfo("side:zh", "zh-Hans", "AI"))
        assertEquals(listOf("Chinese", "Chinese (Simplified) · AI"), labels(subtitleMenu(zhHant, "zh-Hant", emptyList()).top))
        // chino-web's case: Traditional asked for, Chinese whole, never its own
        // run first and the rest of the language after it.
        val chinese = listOf(
            SubtitleTrackInfo("emb:2", "zh-Hant", "AI"),
            SubtitleTrackInfo("emb:3", "zh-Hans"),
            SubtitleTrackInfo("emb:4", "zh-Hant"),
            SubtitleTrackInfo("emb:5", "zh-CN"),
            SubtitleTrackInfo("emb:6", "chi"),
        )
        assertEquals(
            listOf("Chinese", "Chinese (China)", "Chinese (Simplified)", "Chinese (Traditional)", "Chinese (Traditional) · AI"),
            labels(subtitleMenu(chinese, "zh-Hant", emptyList()).top),
        )
        // And with a region: Brazil's and Portugal's in alphabetical order,
        // whichever is asked for; the others behind "more languages".
        val portuguese = listOf(
            SubtitleTrackInfo("emb:2", "eng"),
            SubtitleTrackInfo("emb:3", "pt-PT"),
            SubtitleTrackInfo("emb:4", "por", "Português (Brasil)"),
            SubtitleTrackInfo("side:pt", "pt-BR", "AI"),
        )
        for (asked in listOf("pt-BR", "pt-PT")) {
            val menu = subtitleMenu(portuguese, asked, emptyList())
            assertEquals(listOf("Portuguese (Brazil)", "Portuguese (Brazil) · AI", "Portuguese (Portugal)"), labels(menu.top))
            assertEquals(listOf("English"), labels(menu.more))
        }
        // The TV's region is no script: "en-US" is English, every English.
        val english = listOf(SubtitleTrackInfo("emb:2", "en-GB"), SubtitleTrackInfo("emb:3", "eng"), SubtitleTrackInfo("emb:4", "fre"))
        assertEquals(
            listOf("English", "English (United Kingdom)"),
            labels(subtitleMenu(english, "off", emptyList(), own = ownLanguages(null, deviceLanguage(Locale.US))).top),
        )
    }

    @Test
    fun `more languages in alphabetical order of their names, a track in no language by its own`() {
        val tracks = listOf(
            SubtitleTrackInfo("emb:2", "jpn"),
            SubtitleTrackInfo("emb:3", "und", "Signs"),
            SubtitleTrackInfo("emb:4", "zh-Hant"),
            SubtitleTrackInfo("emb:5", "fre"),
            SubtitleTrackInfo("emb:6", "und", "English"),
            SubtitleTrackInfo("emb:7", "ger"),
        )
        assertEquals(
            listOf("Chinese (Traditional)", "English", "French", "Japanese", "Signs"),
            labels(subtitleMenu(tracks, "off", listOf("de")).more),
        )
    }

    @Test
    fun `a track tagged with no language that reads as one shows once, the file's own, in its twin's language`() {
        // The file's untagged track titled "English", a French one, and the
        // server's English: they read the same and show once - the file's
        // own, timed to it, with the sidecar's language: on top, and on by
        // itself.
        val tracks = listOf(
            SubtitleTrackInfo("emb:2", "und", "English"),
            SubtitleTrackInfo("emb:3", "fre"),
            SubtitleTrackInfo("side:1", "en"),
        )
        val menu = subtitleMenu(tracks, "en", listOf("en", "de", "zh-Hans"))
        assertEquals(listOf(Triple("emb:2", "English", "en")), menu.top.map { Triple(it.id, it.label, it.tag) })
        assertEquals(listOf("emb:3" to "French"), menu.more.map { it.id to it.label })
        assertEquals("emb:2", autoSubtitle(menu, audioLang = "fre", preferred = "en")?.id)
        // The same without the server's languages, and with no preferred language.
        val noServer = subtitleMenu(tracks, "en", emptyList())
        assertEquals(listOf("emb:2"), noServer.top.map { it.id })
        assertEquals(listOf("emb:3"), noServer.more.map { it.id })
        assertEquals(listOf("emb:2", "emb:3"), subtitleMenu(tracks, "off", emptyList()).all.map { it.id })
        // Alone, it is listed by its title.
        assertEquals(listOf("emb:2" to "English", "emb:3" to "French"), subtitleMenu(tracks.take(2), "en", listOf("de")).more.map { it.id to it.label })
        // Two of the file's own, the untagged one first: the first, in English.
        val both = subtitleMenu(listOf(SubtitleTrackInfo("emb:2", "und", "English"), SubtitleTrackInfo("emb:3", "eng")), "en", listOf("en"))
        assertEquals(listOf("emb:2" to "en"), both.top.map { it.id to it.tag })
        assertEquals("emb:2", autoSubtitle(both, audioLang = "fre", preferred = "en")?.id)
        // The foreign-language parts only, too: English audio, the file's own forced track.
        val forced = subtitleMenu(
            listOf(SubtitleTrackInfo("emb:4", "und", "English Forced"), SubtitleTrackInfo("side:2", "eng", forced = true)),
            "off",
            listOf("en"),
        )
        assertEquals(listOf("English · foreign-language parts only"), labels(forced.all))
        assertEquals("emb:4", autoSubtitle(forced, audioLang = "eng", preferred = "off")?.id)
        // Untagged tracks that read the same are one.
        assertEquals(1, subtitleMenu(listOf(SubtitleTrackInfo("a", "und", "Signs"), SubtitleTrackInfo("b", "", "Signs")), "off", listOf("en")).more.size)
    }

    @Test
    fun `never numbered, each label once, in any language or kind`() {
        val extra = tracks + listOf(
            SubtitleTrackInfo("emb:50", "ger"),
            SubtitleTrackInfo("emb:51", "jpn", "日本語"),
            SubtitleTrackInfo("emb:52", "eng", "SDH"),
            SubtitleTrackInfo("emb:53", "und", "Signs"),
            SubtitleTrackInfo("emb:54", "und", "English"),
            SubtitleTrackInfo("side:en-2", "eng", "English (2)"),
            SubtitleTrackInfo("side:zh-2", "zh-Hans", "AI (2)"),
        )
        for (languages in listOf(server, emptyList())) {
            val all = subtitleMenu(extra, "eng", languages).all
            assertTrue(all.none { Regex("""\(\d+\)""").containsMatchIn(it.label) })
            assertEquals(all.size, all.map { it.label }.toSet().size)
        }
    }

    @Test
    fun `a region's adjective and a code read as the region, the same region once, two regions or scripts two`() {
        // The file's Brazilian Portuguese and the server's are one, the file's own.
        val brazil = listOf(SubtitleTrackInfo("emb:2", "por", "Portuguese · Brazilian"), SubtitleTrackInfo("side:pt", "pt-BR"))
        for (languages in listOf(emptyList(), listOf("en", "pt-BR"))) {
            assertEquals(listOf("emb:2" to "Portuguese (Brazil)"), subtitleMenu(brazil, "off", languages).all.map { it.id to it.label })
        }
        val pairs = listOf(
            Triple("por", "PT-BR" to "PT-PT", listOf("Portuguese (Brazil)", "Portuguese (Portugal)")),
            Triple("chi", "zh-Hant" to "zh-Hans", listOf("Chinese (Simplified)", "Chinese (Traditional)")),
            Triple("spa", "es-419" to "es-ES", listOf("Spanish (Latin America)", "Spanish (Spain)")),
            Triple("por", "Português (BR)" to "Português (PT)", listOf("Portuguese (Brazil)", "Portuguese (Portugal)")),
        )
        for ((lang, titles, expected) in pairs) {
            val two = listOf(SubtitleTrackInfo("emb:2", lang, titles.first), SubtitleTrackInfo("emb:3", lang, titles.second))
            // Without the server's languages, each region its own run, in
            // alphabetical order...
            assertEquals(titles.toString(), expected, labels(subtitleMenu(two, "off", emptyList()).all))
            // ...and with them, both still there to pick.
            assertEquals(titles.toString(), setOf("emb:2", "emb:3"), subtitleMenu(two, "off", listOf("en")).all.map { it.id }.toSet())
        }
        // The same region twice, as a code and by its name, is one.
        val twice = listOf(SubtitleTrackInfo("emb:2", "por", "PT-BR"), SubtitleTrackInfo("side:pt", "pt-BR"))
        assertEquals(listOf("emb:2" to "Portuguese (Brazil)"), subtitleMenu(twice, "off", listOf("en")).all.map { it.id to it.label })
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
    fun `the preferred subtitle language where the title has it and the audio is in another, its plain track`() {
        assertEquals("emb:8", autoSubtitle(subtitleMenu(tracks, "de", server), audioLang = "eng", preferred = "de")?.id)
        assertEquals("emb:0", autoSubtitle(subtitleMenu(tracks, "en", server), audioLang = "jpn", preferred = "en")?.id)
        // English subtitles over English audio are the viewer's to pick.
        assertNull(autoSubtitle(subtitleMenu(tracks, "en", server), audioLang = "eng", preferred = "en"))
        assertNull(autoSubtitle(subtitleMenu(tracks, "de", server), audioLang = "deu", preferred = "ger"))
        // No language known for the audio: nothing.
        assertNull(autoSubtitle(subtitleMenu(tracks, "de", server), audioLang = "und", preferred = "de"))
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
        // English preferred over English audio: the foreign-language parts only.
        assertEquals("side:en-forced", autoSubtitle(dubbedMenu, audioLang = "eng", preferred = "en")?.id)
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
