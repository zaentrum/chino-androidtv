package cloud.nalet.chino.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audio and subtitle menus' labels: the language first, its script or
 *  region in brackets, "No dialogue" for zxx, "Unknown" for none, a
 *  subtitle's kind in words; chino-web's languages.test.ts cases and
 *  chino-mobile's LanguagesTest, and the names chino-stream gives a file's
 *  subtitle streams. */
class TrackLabelsTest {
    @Test
    fun languagesByTheirEnglishNameInEverySpelling() {
        for (tag in listOf("ger", "deu", "de", "DE")) assertEquals(tag, "German", languageName(tag))
        // A region in the tag is in the name, in brackets.
        assertEquals("German (Switzerland)", languageName("de-CH"))
        assertEquals("English", languageName("en"))
        assertEquals("French", languageName("fre"))
        assertEquals("Dutch", languageName("nl"))
        assertEquals("Japanese", languageName("ja"))
        assertEquals("Unknown", languageName("und"))
        assertEquals("Unknown", languageName(""))
        assertEquals("Unknown", languageName(null))
        assertEquals("Unknown", languageName("english"))
        // A code there is no name for is shown as it came.
        assertEquals("qaa", languageName("qaa"))
    }

    @Test
    fun zxxNoLinguisticContentIsAFilmWithoutDialogue() {
        for (tag in listOf("zxx", "ZXX", " zxx ", "zxx-Latn")) {
            assertEquals(tag, "No dialogue", languageName(tag))
            assertTrue(tag, isNoDialogue(tag))
        }
        assertFalse(isNoDialogue("und"))
        assertFalse(isNoDialogue(null))
    }

    @Test
    fun mulIsMultipleLanguagesAndMisOtherLanguage() {
        assertEquals("Multiple languages", languageName("mul"))
        assertEquals("Multiple languages", languageName("MUL"))
        assertEquals("Other language", languageName("mis"))
        assertEquals(
            listOf("Multiple languages", "Other language", "Multiple languages · Original"),
            audioLabels(listOf(AudioLabelInput("mul", name = "mul"), AudioLabelInput("mis"), AudioLabelInput("mul", name = "Original"))),
        )
        assertEquals(
            listOf("Multiple languages", "Other language · Signs"),
            subtitleLabels(listOf(SubtitleLabelInput("mul"), SubtitleLabelInput("mis", title = "Signs"))),
        )
        assertEquals("Multiple languages", languageOrLabel("mul", "mul"))
    }

    @Test
    fun aTitlesSubtitleLanguagesByNameATrackInNoLanguageByItsLabel() {
        assertEquals("German", languageOrLabel("ger", null))
        assertEquals("English", languageOrLabel("eng", "English (SDH)"))
        assertEquals("No dialogue", languageOrLabel("zxx", "zxx"))
        assertEquals("Signs", languageOrLabel("und", " Signs "))
        assertEquals(null, languageOrLabel("und", null))
        assertEquals(null, languageOrLabel("", " "))
    }

    @Test
    fun audioByItsLanguageFirstNoDialogueForZxxUnknownForNone() {
        assertEquals(
            listOf("English", "No dialogue", "Unknown", "German"),
            audioLabels(
                listOf(
                    AudioLabelInput("en", name = "English"),
                    AudioLabelInput("zxx", name = "No dialogue"),
                    AudioLabelInput("und", name = "Unknown"),
                    AudioLabelInput("de", name = "Deutsch"),
                ),
            ),
        )
    }

    @Test
    fun anOldPlaylistsFreeTextNamesAFormatANumberOrACodeIsNoLabel() {
        assertEquals(
            listOf("English", "French", "No dialogue", "Unknown", "Unknown (2)", "Unknown (3)"),
            audioLabels(
                listOf(
                    AudioLabelInput("en", name = "AC3 5.1 @ 640 Kbps"),
                    AudioLabelInput("fr", name = "DTS-HD Master Audio / 5.1 / 48 kHz / 2618 kbps / 24-bit"),
                    AudioLabelInput("zxx", name = "zxx"),
                    AudioLabelInput("und", name = "Track 0"),
                    AudioLabelInput(null, name = "Dolby Digital 5.1"),
                    AudioLabelInput("und", name = "und"),
                ),
            ),
        )
    }

    @Test
    fun aTrackInNoLanguageIsCalledWhatItsNameSays() {
        assertEquals(
            listOf("Director's Commentary", "Commentary", "Unknown", "Klingon"),
            audioLabels(
                listOf(
                    AudioLabelInput("und", name = "Director's Commentary"),
                    AudioLabelInput(null, name = "Commentary 5.1"),
                    AudioLabelInput(null, name = "Stereo"),
                    AudioLabelInput("qaa", name = "Klingon"),
                ),
            ),
        )
    }

    @Test
    fun twoOfOneLanguageToldApartByTheirNamesElseNumberedTheLayoutTellsThemApartToo() {
        assertEquals(
            listOf("English", "English · Commentary", "English (2)", "English", "English · Commentary"),
            audioLabels(
                listOf(
                    AudioLabelInput("en", name = "English", detail = "Stereo • mp4a.40.2"),
                    AudioLabelInput("en", name = "Commentary", detail = "Stereo • mp4a.40.2"),
                    AudioLabelInput("en", name = "English (2)", detail = "Stereo • mp4a.40.2"),
                    AudioLabelInput("en", name = "English 5.1", detail = "5.1 • ec-3"),
                    AudioLabelInput("en", name = "Commentary 5.1", detail = "5.1 • ec-3"),
                ),
            ),
        )
    }

    @Test
    fun aFiveOneCompanionBesideItsStereoTwinReadsAsItsLanguageItsLayoutAndCodecBeside() {
        // chino-stream's one group for a TV that plays E-AC-3: "English 5.1"
        // just before "English", the two told apart by what the row shows
        // beside the label.
        assertEquals(
            listOf("English", "English"),
            audioLabels(
                listOf(
                    AudioLabelInput("en", name = "English 5.1", detail = "5.1 • ec-3"),
                    AudioLabelInput("en", name = "English", detail = "Stereo • mp4a.40.2"),
                ),
            ),
        )
        // Media3 leaves the companion's codecs unset - the variant names two
        // audio codecs - so the row takes its sample format's.
        assertEquals("ec-3", audioCodec(null, "audio/eac3"))
        assertEquals("ec-3", audioCodec(" ", "audio/eac3-joc"))
        assertEquals("ac-3", audioCodec(null, "audio/ac3"))
        assertEquals("mp4a.40.2", audioCodec("mp4a.40.2", "audio/mp4a-latm"))
        assertEquals(null, audioCodec(null, "audio/mp4a-latm"))
        assertEquals(null, audioCodec(null, null))
    }

    @Test
    fun subtitlesByLanguageWithWhatTheirLabelSaysBeyondItAndTheKindInWords() {
        assertEquals(
            listOf(
                "German", "Dutch", "English · for deaf and hard of hearing", "English · for deaf and hard of hearing (2)",
                "English", "German · foreign-language parts only", "No dialogue", "No dialogue · Signs", "Signs & Songs",
                "Unknown", "Unknown (2)",
            ),
            subtitleLabels(
                listOf(
                    SubtitleLabelInput("de"),
                    SubtitleLabelInput("nl", title = "nl"),
                    SubtitleLabelInput("en", title = "SDH"),
                    SubtitleLabelInput("en", title = "English [CC]"),
                    SubtitleLabelInput("en", title = "English"),
                    SubtitleLabelInput("de", forced = true),
                    SubtitleLabelInput("zxx"),
                    SubtitleLabelInput("zxx", title = "Signs"),
                    SubtitleLabelInput("und", title = "Signs & Songs"),
                    SubtitleLabelInput("und", title = "und"),
                    SubtitleLabelInput(null),
                ),
            ),
        )
    }

    // chino-mobile's LanguagesTest (release-0.2): codes as one language, the
    // names with their script or region, and the kinds in words.

    @Test
    fun codesInEverySpellingTheLibraryUsesAreOneLanguage() {
        val cases = mapOf(
            "de" to listOf("ger", "deu", "de", "DE", "de-CH", "de_AT"),
            "fr" to listOf("fre", "fra", "fr"),
            "nl" to listOf("dut", "nld", "nl"),
            "en" to listOf("eng", "en", "en-US"),
            "zh" to listOf("chi", "zho", "zh", "zh-Hant"),
            "cs" to listOf("cze", "ces", "cs"),
            "el" to listOf("gre", "ell", "el"),
            "ro" to listOf("rum", "ron", "ro"),
            "es" to listOf("spa", "es"),
            "he" to listOf("iw", "heb", "he"),
        )
        for ((lang, codes) in cases) for (code in codes) assertEquals(code, lang, normalizeLang(code))
        // A three-letter code with no two-letter one stays as it is.
        assertEquals("fil", normalizeLang("fil"))
        assertEquals("yue", normalizeLang("yue"))
        // One the table lacks is the platform's two letters.
        assertEquals("rw", normalizeLang("kin"))
        for (code in listOf("und", "zxx", "mul", "mis", "", "  ", "english", "e", "12", null)) {
            assertEquals(code.toString(), "", normalizeLang(code))
        }
    }

    @Test
    fun aTagKeepsItsRegionOrScriptCanonicallyCased() {
        assertEquals("pt-BR", languageTag("pt_br"))
        assertEquals("de", languageTag("ger"))
        assertEquals("zh-Hant", languageTag("zh-hant"))
        assertEquals("es-419", languageTag("spa-419"))
        assertEquals("", languageTag("und"))
    }

    @Test
    fun aScriptOrARegionStaysInTheNameInBracketsAfterTheLanguage() {
        assertEquals("Chinese (Simplified)", languageName("zh-Hans"))
        assertEquals("Chinese (Traditional)", languageName("zh-hant"))
        assertEquals("Chinese (Traditional, Taiwan)", languageName("zh-Hant-TW"))
        assertEquals("Portuguese (Brazil)", languageName("pt_br"))
        assertEquals("Spanish (Latin America)", languageName("spa-419"))
        assertEquals("English (New Zealand)", languageName("en-NZ"))
        assertEquals("Serbian (Latin, Serbia)", languageName("sr-Latn-RS"))
        // A region the table has no name for: its code.
        assertEquals("English (GU)", languageName("en-GU"))
        // A variant or an extension names nothing.
        assertEquals("German", languageName("de-1901"))
        assertEquals("English (United States)", languageName("en-US-x-custom"))
        // The languages of an original file's streams the table names, by
        // their three letters.
        assertEquals(
            listOf("Bulgarian", "Estonian", "Indonesian", "Lithuanian", "Latvian", "Malay", "Slovak", "Slovenian", "Tamil", "Telugu"),
            listOf("bul", "est", "ind", "lit", "lav", "may", "slo", "slv", "tam", "tel").map(::languageName),
        )
    }

    @Test
    fun theKindInWordsAfterTheLanguageForcedFirst() {
        assertEquals(
            listOf(
                "English · for deaf and hard of hearing",
                "English · foreign-language parts only",
                "English · foreign-language parts only · for deaf and hard of hearing",
                "Chinese (Simplified) · foreign-language parts only",
            ),
            subtitleLabels(
                listOf(
                    SubtitleLabelInput("eng", sdh = true),
                    SubtitleLabelInput("eng", forced = true),
                    SubtitleLabelInput("eng", forced = true, sdh = true),
                    SubtitleLabelInput("zh-Hans", forced = true),
                ),
            ),
        )
    }

    @Test
    fun aLabelThatSaysTheKindItselfSaysItOnceInTheseWords() {
        val sdh = "English · for deaf and hard of hearing"
        val forced = "English · foreign-language parts only"
        val both = "English · foreign-language parts only · for deaf and hard of hearing"
        val cases = mapOf(
            "English SDH" to sdh,
            "SDH" to sdh,
            "English [CC]" to sdh,
            "English (Closed Captions)" to sdh,
            "English - Hearing Impaired" to sdh,
            "English (for the deaf and hard of hearing)" to sdh,
            // chino-stream's name, and this rule's own words again.
            "English · SDH" to sdh,
            sdh to sdh,
            "English (Forced)" to forced,
            "English (forced)" to forced,
            "Forced" to forced,
            "English Forced Narrative" to forced,
            forced to forced,
            "English · SDH (forced)" to both,
            "Forced, SDH" to both,
            both to both,
        )
        for ((title, label) in cases) assertEquals(title, label, subtitleLabel(SubtitleLabelInput("eng", title)))
        // A title that says nothing of a kind is no kind.
        assertEquals("English", subtitleLabel(SubtitleLabelInput("eng", "English")))
    }

    @Test
    fun whatElseATitleSaysStaysBeforeTheKind() {
        assertEquals(
            listOf(
                "English · Commentary",
                "English Commentary",
                "English · Commentary · for deaf and hard of hearing",
                "German · Signs & Songs · foreign-language parts only",
                // "English" on a British English track: the name says it.
                "English (United Kingdom)",
                // A made track's label, its script in the name already.
                "Chinese (Simplified) · Converted",
            ),
            subtitleLabels(
                listOf(
                    SubtitleLabelInput("eng", title = "Commentary"),
                    SubtitleLabelInput("eng", title = "English Commentary"),
                    SubtitleLabelInput("eng", title = "Commentary (SDH)"),
                    SubtitleLabelInput("ger", title = "Signs & Songs", forced = true),
                    SubtitleLabelInput("en-GB", title = "English"),
                    SubtitleLabelInput("zh-Hans", title = "Chinese (Simplified) · Converted"),
                ),
            ),
        )
    }

    // The names chino-stream gives an original file's streams, and the
    // attached sidecars' label.

    @Test
    fun aNameThatSaysTheScriptOrRegionPutsItInBracketsAfterTheLanguage() {
        val cases = listOf(
            Triple("chi", "Chinese · Simplified", "Chinese (Simplified)"),
            Triple("chi", "Chinese · Traditional", "Chinese (Traditional)"),
            Triple("por", "Portuguese · Brazilian", "Portuguese (Brazil)"),
            Triple("spa", "Spanish · Latin American", "Spanish (Latin America)"),
            Triple("fre", "French · Canadian", "French (Canada)"),
            Triple("chi", "Simplified Chinese", "Chinese (Simplified)"),
            Triple("chi", "Chinese (Simplified)", "Chinese (Simplified)"),
            Triple("chi", "简体", "Chinese (Simplified)"),
            Triple("chi", "繁體中文", "Chinese (Traditional) · 中文"),
            Triple("eng", "English (US)", "English (United States)"),
            // The kind and the other words stay, in their places.
            Triple("chi", "Chinese · Simplified · SDH", "Chinese (Simplified) · for deaf and hard of hearing"),
            Triple("por", "Brazilian (Forced)", "Portuguese (Brazil) · foreign-language parts only"),
            Triple("spa", "Spanish · Latin American · Commentary", "Spanish (Latin America) · Commentary"),
        )
        for ((lang, name, label) in cases) assertEquals(name, label, subtitleLabel(SubtitleLabelInput(lang, name)))
        // A word that names a variant of another language says nothing here:
        // "Canadian" is French (Canada), and of a German track only a word.
        assertEquals("German · Canadian", subtitleLabel(SubtitleLabelInput("ger", "Canadian")))
        // A script or region the tag has already is the one.
        assertEquals("Chinese (Traditional) · Simplified", subtitleLabel(SubtitleLabelInput("zh-Hant", "Simplified")))
        // "us" in a sentence is no region.
        assertEquals("English · talk to us", subtitleLabel(SubtitleLabelInput("eng", "talk to us")))
    }

    @Test
    fun theTagOfASubtitleCarriesWhatItsNameSays() {
        assertEquals("zh-Hans", subtitleTag("chi", "Chinese · Simplified").tag)
        assertEquals("zh-Hant", subtitleTag("chi", "Chinese · Traditional").tag)
        assertEquals("pt-BR", subtitleTag("por", "Portuguese · Brazilian").tag)
        assertEquals("es-419", subtitleTag("spa", "Spanish · Latin American").tag)
        assertEquals("zh-Hans", subtitleTag("zh-Hans", "AI").tag)
        assertEquals("en", subtitleTag("eng", "English · SDH").tag)
        assertEquals("", subtitleTag("und", "Signs").tag)
    }

    @Test
    fun anAttachedSidecarsLabelStaysAfterTheLanguage() {
        assertEquals(
            listOf("German · AI", "English · AI", "Chinese (Simplified) · AI"),
            subtitleLabels(listOf(SubtitleLabelInput("de", "AI"), SubtitleLabelInput("en", "AI"), SubtitleLabelInput("zh-Hans", "AI"))),
        )
    }

    @Test
    fun theSameLabelTwiceIsNumberedTheServersNumberNoMore() {
        assertEquals(
            listOf(
                "English",
                "English (2)",
                "English (3)",
                "English · for deaf and hard of hearing",
                "English · for deaf and hard of hearing (2)",
                "German · foreign-language parts only",
                "German · foreign-language parts only (2)",
                "Unknown",
            ),
            subtitleLabels(
                listOf(
                    SubtitleLabelInput("eng", title = "eng"),
                    SubtitleLabelInput("eng", title = "English"),
                    // chino-stream's unique NAME: numbered by the labels, once.
                    SubtitleLabelInput("eng", title = "English (2)"),
                    SubtitleLabelInput("eng", title = "SDH"),
                    SubtitleLabelInput("eng", title = "English · SDH (2)"),
                    SubtitleLabelInput("ger", forced = true),
                    SubtitleLabelInput("ger", title = "Forced", forced = true),
                    SubtitleLabelInput("und"),
                ),
            ),
        )
    }

    @Test
    fun aSubtitleForTheDeafSaysSo() {
        assertTrue(isSdhSubtitle("English SDH"))
        assertTrue(isSdhSubtitle("English (CC)"))
        assertTrue(isSdhSubtitle("Hard of hearing"))
        assertTrue(isSdhSubtitle(null, "public.accessibility.describes-music-and-sound"))
        assertFalse(isSdhSubtitle("English"))
        assertFalse(isSdhSubtitle("Ascii"))
        assertFalse(isSdhSubtitle(null))
    }

    @Test
    fun portugueseOfBrazilAndOtherVariantsReadTheSameInTheAudioMenu() {
        assertEquals(
            listOf("English", "No dialogue", "Unknown", "German", "Portuguese (Brazil)"),
            audioLabels(
                listOf(
                    AudioLabelInput("eng"),
                    AudioLabelInput("zxx"),
                    AudioLabelInput("und"),
                    AudioLabelInput("ger", name = "Deutsch"),
                    AudioLabelInput("pt-BR"),
                ),
            ),
        )
    }
}
