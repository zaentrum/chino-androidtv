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
            listOf(SubtitleLabelInput("mul"), SubtitleLabelInput("mis", title = "Signs")).map(::subtitleLabel),
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
    fun aMacrolanguagesOwnLanguageAsMedia3WritesItIsThatLanguage() {
        // Media3's normalizeLanguageCode: "ind" is "ms-ind", "hrv" "hbs-hrv".
        val cases = listOf(
            "ms-ind" to "Indonesian", "hbs-hrv" to "Croatian", "hbs-srp" to "Serbian", "hbs-bos" to "Bosnian",
            "no-nob" to "Norwegian Bokmål", "no-nno" to "Norwegian Nynorsk",
        )
        for ((tag, name) in cases) assertEquals(tag, name, languageName(tag))
        assertEquals("id", normalizeLang("ms-ind"))
        assertEquals("hr", languageTag("hbs-hrv"))
        // A file's own code beside it says nothing more.
        assertEquals("Indonesian", subtitleLabel(SubtitleLabelInput("ms-ind", "ind")))
        assertEquals("Croatian · foreign-language parts only", subtitleLabel(SubtitleLabelInput("hbs-hrv", "Hrvatski", forced = true)))
        // A region is no extlang.
        assertEquals("Spanish (Latin America)", languageName("es-419"))
        assertEquals("Chinese (Traditional, Taiwan)", languageName("zh-Hant-TW"))
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
        // A region the table has no name for: the platform's, as the web's
        // Intl names it - the spec's own example.
        assertEquals("English (Guam)", languageName("en-GU"))
        // The regions a title names that the table lacks, by their names.
        assertEquals(
            listOf(
                "Spanish (Bolivia)", "Spanish (Ecuador)", "Spanish (Paraguay)", "Spanish (Dominican Republic)",
                "Spanish (Guatemala)", "Spanish (Honduras)", "Spanish (Nicaragua)", "Spanish (Panama)",
                "Spanish (El Salvador)", "Spanish (Costa Rica)", "Spanish (Puerto Rico)", "English (Jamaica)",
            ),
            listOf("es-BO", "es-EC", "es-PY", "es-DO", "es-GT", "es-HN", "es-NI", "es-PA", "es-SV", "es-CR", "es-PR", "en-JM")
                .map(::languageName),
        )
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

    /** One subtitle as chino-web's languages.test.ts asks for it: the track,
     *  the name it gets, and the language the menu lists it by (where the
     *  test says). */
    private data class Case(
        val lang: String?,
        val title: String?,
        val label: String,
        val forced: Boolean = false,
        val sdh: Boolean = false,
        val tag: String? = null,
    )

    private fun check(cases: List<Case>) {
        for (c in cases) {
            val input = SubtitleLabelInput(c.lang, c.title, c.forced, c.sdh)
            assertEquals("${c.lang} \"${c.title}\"", c.label, subtitleLabel(input))
            c.tag?.let { assertEquals("${c.lang} \"${c.title}\" is listed as", it, subtitleLanguage(c.lang, c.title)) }
        }
    }

    // chino-web's languages.test.ts (subtitles-spec), case for case: the same
    // track has the same name on the TV, the phone and the web.

    @Test
    fun theDemosSintelEveryTrackNamedNoneBlank() = check(
        listOf("ger" to "German", "dut" to "Dutch", "eng" to "English", "fre" to "French", "ita" to "Italian",
            "spa" to "Spanish", "pol" to "Polish", "por" to "Portuguese", "rus" to "Russian", "vie" to "Vietnamese")
            .map { (lang, label) -> Case(lang, null, label) } + Case("tgl", "Tagalog", "Tagalog"),
    )

    @Test
    fun theKindInWordsAfterTheLanguageForcedFirst() = check(
        listOf(
            Case("eng", null, "English · for deaf and hard of hearing", sdh = true),
            Case("eng", null, "English · foreign-language parts only", forced = true),
            Case("eng", null, "English · foreign-language parts only · for deaf and hard of hearing", forced = true, sdh = true),
            Case("zh-Hans", null, "Chinese (Simplified) · foreign-language parts only", forced = true),
            Case("eng", "English", "English"),
            Case("eng", "English SDH", "English · for deaf and hard of hearing"),
            Case("ger", null, "German"),
            Case("ger", "German (forced)", "German · foreign-language parts only", forced = true),
        ),
    )

    @Test
    fun aLabelThatSaysTheKindItselfSaysItOnceInTheseWords() {
        val sdh = "English · for deaf and hard of hearing"
        val forced = "English · foreign-language parts only"
        val both = "English · foreign-language parts only · for deaf and hard of hearing"
        check(
            listOf(
                "English SDH" to sdh, "SDH" to sdh, "English [CC]" to sdh, "[CC]" to sdh,
                "English (Closed Captions)" to sdh, "English - Hearing Impaired" to sdh,
                "English (for the deaf and hard of hearing)" to sdh,
                // A packager's NAME, and this rule's own words again.
                "English · SDH" to sdh, sdh to sdh,
                "English (Forced)" to forced, "Forced" to forced, "English Forced Narrative" to forced, forced to forced,
                "English · SDH (forced)" to both, "Forced, SDH" to both, both to both,
                // A title that says nothing of a kind is no kind.
                "English" to "English",
            ).map { (title, label) -> Case("eng", title, label) },
        )
        assertTrue(isSdhSubtitle("English [CC]"))
        assertFalse(isSdhSubtitle("Commentary"))
        assertTrue(saysForced("German (Forced)"))
        assertFalse(saysForced("Reinforced"))
    }

    @Test
    fun wordsThatOnlyNameTheLanguageAgainGoInAnyLanguageWordsThatAddSomethingStay() = check(
        listOf(
            // The word list's name, never "German · Deutsch".
            Case("de", "Deutsch", "German"),
            Case("de", "German · Deutsch", "German"),
            Case("zh-hans", "中文", "Chinese (Simplified)"),
            Case("zh-Hans", "Chinese (Simplified) · 中文", "Chinese (Simplified)"),
            Case("zh-Hant", "繁體中文", "Chinese (Traditional)"),
            Case("pt-br", "Português", "Portuguese (Brazil)"),
            Case("spa", "Español", "Spanish"),
            Case("ja", "日本語", "Japanese"),
            // Named in a third language: still only the language.
            Case("ger", "Allemand", "German"),
            Case("de", "Deutsch (Forced)", "German · foreign-language parts only"),
            Case("de", "Deutsch · Kommentar", "German · Kommentar"),
            // Another language's own name is something else.
            Case("de", "Español", "German · Español"),
            // Inside a part with other words, only the language's name goes -
            // not the label's own name, which says it once - and a word that is
            // a code elsewhere is a word ("und" is German for "and").
            Case("de", "Deutsch Kommentar", "German · Kommentar"),
            Case("de", "Kommentar und Analyse", "German · Kommentar und Analyse"),
            Case("eng", "English Commentary", "English Commentary"),
        ),
    )

    @Test
    fun aChineseTracksTitleThatGivesItsScriptNamesItByItsScript() {
        check(
            listOf("简体中文", "簡體中文", "简体", "CHS", "Simplified", "Chinese (Simplified)", "Simplified Chinese")
                .map { Case("chi", it, "Chinese (Simplified)", tag = "zh-Hans") } +
                listOf("繁體中文", "繁体中文", "繁體", "CHT", "Traditional")
                    .flatMap { listOf(Case("chi", it, "Chinese (Traditional)"), Case("zho", it, "Chinese (Traditional)", tag = "zh-Hant")) } +
                listOf(
                    // Its region in brackets, the full-width ones too.
                    Case("chi", "繁體中文（台灣）", "Chinese (Traditional, Taiwan)"),
                    // A tag that names the other script: the title's word stays.
                    Case("zh-Hans", "繁體中文", "Chinese (Simplified) · 繁體中文"),
                    // Mandarin and Cantonese in the word list's words.
                    Case("chi", "國語", "Chinese · Mandarin"),
                    Case("chi", "普通话", "Chinese · Mandarin"),
                    Case("chi", "粵語", "Chinese · Cantonese"),
                    Case("chi", "中文", "Chinese", tag = "zh"),
                ),
        )
    }

    @Test
    fun aLanguagesOwnNameBeforeARegionTheRegionInBracketsAfterTheLanguage() = check(
        listOf(
            Case("spa", "Español (Latinoamérica)", "Spanish (Latin America)", tag = "es-419"),
            Case("spa", "Español (España)", "Spanish (Spain)", tag = "es-ES"),
            Case("spa", "Español Latino", "Spanish (Latin America)", tag = "es-419"),
            Case("spa", "Latinoamerica", "Spanish (Latin America)", tag = "es-419"),
            Case("fre", "Français (Canada)", "French (Canada)", tag = "fr-CA"),
            Case("fre", "Français canadien", "French (Canada)", tag = "fr-CA"),
            Case("pt", "Português (Brasil)", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("por", "Português - Brasil", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("spa", "Spanish (Latin America)", "Spanish (Latin America)", tag = "es-419"),
            // The tag's region again says nothing more.
            Case("es-419", "Español (Latinoamérica)", "Spanish (Latin America)", tag = "es-419"),
            // A title in the dialect form names the region: said in the
            // standard form, the language and its region in brackets.
            Case("por", "Brazilian Portuguese", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("por", "European Portuguese", "Portuguese (Portugal)", tag = "pt-PT"),
            Case("spa", "Latin American Spanish", "Spanish (Latin America)", tag = "es-419"),
            Case("fre", "Canadian French", "French (Canada)", tag = "fr-CA"),
            Case("eng", "British English", "English (United Kingdom)", tag = "en-GB"),
            Case("dut", "Flemish", "Dutch (Belgium)", tag = "nl-BE"),
            Case("pt-BR", "Brazilian Portuguese", "Portuguese (Brazil)", tag = "pt-BR"),
            // The dialect's adjective in brackets after the language, or alone.
            Case("por", "Portuguese (Brazilian)", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("por", "Portuguese (European)", "Portuguese (Portugal)", tag = "pt-PT"),
            Case("spa", "Spanish (Latin American)", "Spanish (Latin America)", tag = "es-419"),
            Case("spa", "Spanish (Mexican)", "Spanish (Mexico)", tag = "es-MX"),
            Case("fre", "French (Canadian)", "French (Canada)", tag = "fr-CA"),
            Case("eng", "English (British)", "English (United Kingdom)", tag = "en-GB"),
            Case("eng", "English [American]", "English (United States)", tag = "en-US"),
            Case("ger", "German (Austrian)", "German (Austria)", tag = "de-AT"),
            Case("por", "Brazilian", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("por", "Portuguese - Brazilian", "Portuguese (Brazil)", tag = "pt-BR"),
            Case(
                "spa", "Spanish (Latin American) (Forced)", "Spanish (Latin America) · foreign-language parts only",
                tag = "es-419",
            ),
            // Another region than the tag's stays a word.
            Case("es-ES", "Latinoamérica", "Spanish (Spain) · Latinoamérica"),
            Case("es-ES", "Latin American Spanish", "Spanish (Spain) · Latin American Spanish"),
            Case("pt-PT", "Portuguese (Brazilian)", "Portuguese (Portugal) · Brazilian"),
            // A region among other words is a word, its adjective too.
            Case("fre", "Canada Commentaire", "French · Canada Commentaire"),
            Case("por", "Brazilian Commentary", "Portuguese · Brazilian Commentary"),
            // With the kind: once, after.
            Case("spa", "Español (Latinoamérica) (Forced)", "Spanish (Latin America) · foreign-language parts only"),
        ),
    )

    @Test
    fun aTitleThatIsACodeOfTheTracksLanguageKeepsItsRegionOrScriptInTheWordListsForm() = check(
        listOf(
            Case("por", "PT-BR", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("por", "PT-PT", "Portuguese (Portugal)", tag = "pt-PT"),
            Case("por", "pt_BR", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("chi", "zh-Hant", "Chinese (Traditional)", tag = "zh-Hant"),
            Case("chi", "zh-Hans", "Chinese (Simplified)", tag = "zh-Hans"),
            Case("chi", "zh-Hant-TW", "Chinese (Traditional, Taiwan)", tag = "zh-Hant-TW"),
            Case("spa", "es-419", "Spanish (Latin America)", tag = "es-419"),
            Case("spa", "es-ES", "Spanish (Spain)", tag = "es-ES"),
            Case("eng", "en-US", "English (United States)", tag = "en-US"),
            Case("eng", "en-GB", "English (United Kingdom)", tag = "en-GB"),
            // In brackets after the language's name, and with the kind.
            Case("por", "Português (PT-BR)", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("por", "PT-BR (Forced)", "Portuguese (Brazil) · foreign-language parts only", tag = "pt-BR"),
            // The tag's own again says nothing more; a code alone is the language.
            Case("pt-BR", "PT-BR", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("por", "pt", "Portuguese", tag = "pt"),
            Case("eng", "eng", "English", tag = "en"),
            Case("eng", "en-US-x-custom", "English (United States)", tag = "en-US"),
            // Another region or script than the tag's stays, as the title says it.
            Case("pt-PT", "PT-BR", "Portuguese (Portugal) · PT-BR"),
            Case("zh-Hant", "zh-Hans", "Chinese (Traditional) · zh-Hans"),
            // Another language's code is something else.
            Case("eng", "de", "English · de"),
        ),
    )

    @Test
    fun aRegionsCodeInBracketsAfterTheLanguageIsTheRegionWhereItIsOneOfTheLanguages() = check(
        listOf(
            Case("por", "Português (BR)", "Portuguese (Brazil)", tag = "pt-BR"),
            // The language's own code, and a region of it: the region.
            Case("por", "Português (PT)", "Portuguese (Portugal)", tag = "pt-PT"),
            Case("spa", "Español (419)", "Spanish (Latin America)", tag = "es-419"),
            Case("spa", "Español [MX]", "Spanish (Mexico)", tag = "es-MX"),
            Case("fre", "Français (CA)", "French (Canada)", tag = "fr-CA"),
            Case("ger", "Deutsch (CH)", "German (Switzerland)", tag = "de-CH"),
            Case("chi", "中文（TW）", "Chinese (Taiwan)", tag = "zh-TW"),
            Case("spa", "Español (419) (Forced)", "Spanish (Latin America) · foreign-language parts only", tag = "es-419"),
            // The language's own code where it is none of its regions: the
            // language (El Salvador, Argentina and Canada speak other
            // languages most).
            Case("swe", "Svenska (SV)", "Swedish"),
            Case("ara", "Arabic (AR)", "Arabic"),
            Case("cat", "Català (CA)", "Catalan"),
            // A code in lower case is a language's; other capitals are a word.
            Case("por", "Português (pt)", "Portuguese"),
            Case("eng", "English (BD)", "English · BD"),
            // Another number than a region's is a server's, which goes; 150
            // is Europe, no region of Spanish's.
            Case("spa", "Español (150)", "Spanish · 150"),
        ),
    )

    @Test
    fun aRegionTheTitleNamesIsNamedByThePlatformWhereTheTableLacksIt() = check(
        listOf(
            Case("spa", "Español (Bolivia)", "Spanish (Bolivia)", tag = "es-BO"),
            Case("spa", "Español (El Salvador)", "Spanish (El Salvador)", tag = "es-SV"),
            Case("spa", "Español - Puerto Rico", "Spanish (Puerto Rico)", tag = "es-PR"),
        ),
    )

    @Test
    fun whatElseATitleSaysStaysBeforeTheKind() = check(
        listOf(
            Case("eng", "Commentary", "English · Commentary"),
            Case("eng", "English Commentary", "English Commentary"),
            Case("eng", "Commentary (SDH)", "English · Commentary · for deaf and hard of hearing"),
            Case("ger", "Signs & Songs", "German · Signs & Songs · foreign-language parts only", forced = true),
            // "English" on a British English track: the name says it.
            Case("en-GB", "English", "English (United Kingdom)"),
            // A made track's label, its script in the name already.
            Case("zh-Hans", "Chinese (Simplified) · Converted", "Chinese (Simplified) · Converted"),
            // The language again before what the title adds: said once.
            Case("eng", "English · Commentary", "English · Commentary"),
        ),
    )

    @Test
    fun aSidecarTheServerMadeSaysAiAfterTheLanguage() = check(
        listOf(
            Case("de", "AI", "German · AI", tag = "de"),
            Case("en", "AI", "English · AI"),
            Case("zh-Hans", "AI", "Chinese (Simplified) · AI", tag = "zh-Hans"),
        ),
    )

    @Test
    fun aServersNumberOrALanguageCodeIsNoName() = check(
        // The menu never numbers a subtitle: tracks that read the same show
        // once (SubtitleChoicesTest).
        listOf(
            Case("eng", "eng", "English"),
            Case("eng", "English (2)", "English"),
            Case("eng", "SDH", "English · for deaf and hard of hearing"),
            Case("eng", "English · SDH (2)", "English · for deaf and hard of hearing"),
            Case("ger", null, "German · foreign-language parts only", forced = true),
            Case("ger", "Forced", "German · foreign-language parts only", forced = true),
            Case("und", null, "Unknown"),
        ),
    )

    @Test
    fun subtitlesWithoutDialogueOrInNoKnownLanguage() = check(
        listOf(
            Case("zxx", null, "No dialogue"),
            Case("zxx", "zxx", "No dialogue"),
            Case("zxx", "Signs", "No dialogue · Signs"),
            Case("und", "Signs & Songs", "Signs & Songs"),
            Case("und", "und", "Unknown"),
            Case("", "Forced", "Unknown · foreign-language parts only", forced = true),
            Case(null, null, "Unknown"),
            Case("mul", null, "Multiple languages"),
            Case("mis", "Signs", "Other language · Signs"),
        ),
    )

    // The TV's own: what Media3 and chino-stream hand it.

    @Test
    fun theWordsChinoStreamNamesAStreamWithReadAsTheWebReadsThem() = check(
        listOf(
            Case("chi", "Chinese · Simplified", "Chinese (Simplified)", tag = "zh-Hans"),
            Case("chi", "Chinese · Traditional", "Chinese (Traditional)", tag = "zh-Hant"),
            Case("chi", "Chinese · Simplified · SDH", "Chinese (Simplified) · for deaf and hard of hearing"),
            Case("eng", "English (US)", "English (United States)", tag = "en-US"),
            Case("eng", "English · SDH", "English · for deaf and hard of hearing", tag = "en"),
            Case("und", "Signs", "Signs", tag = ""),
            // A region's adjective, as chino-stream writes a title's
            // "Portuguese (Brazilian)" and "Brazilian Portuguese": the region,
            // in brackets after the language (chino-web 2cda806).
            Case("por", "Portuguese · Brazilian", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("por", "Portuguese · Brazilian Portuguese", "Portuguese (Brazil)", tag = "pt-BR"),
            Case("por", "Portuguese · Brazilian · SDH", "Portuguese (Brazil) · for deaf and hard of hearing", tag = "pt-BR"),
            Case("spa", "Spanish · Latin American", "Spanish (Latin America)", tag = "es-419"),
            Case("fre", "French · Canadian", "French (Canada)", tag = "fr-CA"),
            Case("eng", "English · British", "English (United Kingdom)", tag = "en-GB"),
            Case("dut", "Dutch · Flemish", "Dutch (Belgium)", tag = "nl-BE"),
            // CLDR names no regional form by these: they stay words, as on the
            // web; "Swiss French" is one, "Swiss German" is a language.
            Case("spa", "Spanish (Castilian)", "Spanish · Castilian", tag = "es"),
            Case("fre", "French (Belgian)", "French · Belgian", tag = "fr"),
            Case("ger", "German (Swiss)", "German · Swiss", tag = "de"),
            Case("fre", "French (Swiss)", "French (Switzerland)", tag = "fr-CH"),
            Case("eng", "English (Australian)", "English (Australia)", tag = "en-AU"),
            Case("eng", "English (Canadian)", "English (Canada)", tag = "en-CA"),
            Case("spa", "Spanish (European)", "Spanish (Spain)", tag = "es-ES"),
            // In the language's own words, too.
            Case("spa", "Español de México", "Spanish (Mexico)", tag = "es-MX"),
            Case("ger", "Österreichisches Deutsch", "German (Austria)", tag = "de-AT"),
            // A script the tag has already is the one; a word of another
            // language's region says nothing here.
            Case("zh-Hant", "Simplified", "Chinese (Traditional) · Simplified"),
            Case("ger", "Canadian", "German · Canadian"),
            // What CLDR calls Tagalog in every language only names it again.
            Case("tgl", "Filipino", "Tagalog"),
            Case("fil", "Tagalog", "Filipino · Tagalog"),
            // "us" in a sentence is no region.
            Case("eng", "talk to us", "English · talk to us"),
            // In another language than its own, too.
            Case("eng", "Englisch", "English"),
            Case("ita", "Italiano", "Italian"),
            Case("fre", "Français (Commentaire)", "French · Commentaire"),
            Case("ger", "Français", "German · Français"),
        ),
    )

    @Test
    fun channelsInWordsMonoStereoFiveOneSevenOneNeverACountLike2ch() {
        // chino-web's channelWords, chino-mobile's names for 1, 2, 6 and 8.
        assertEquals(
            listOf("Mono", "Stereo", "2.1", "4.0", "5.0", "5.1", "6.1", "7.1"),
            (1..8).map(::channelWords),
        )
        // Past the table: the number in words, still no "ch".
        assertEquals("12 channels", channelWords(12))
        for (n in listOf(0, -1)) assertEquals(n.toString(), null, channelWords(n))
        for (n in 1..16) assertFalse(n.toString(), Regex("""\dch\b""").containsMatchIn(channelWords(n)!!))
    }

    @Test
    fun theAudioMenusDetailIsTheCodecAndTheChannelsInWordsAsThePhoneSaysIt() {
        assertEquals(listOf("AAC", "Stereo"), audioDetail("mp4a.40.2", 2))
        assertEquals(listOf("E-AC-3", "5.1"), audioDetail(audioCodec(null, "audio/eac3"), 6))
        assertEquals(listOf("AC-3", "5.1"), audioDetail("ac-3", 6))
        assertEquals(listOf("E-AC-3", "7.1"), audioDetail("ec-3", 8))
        assertEquals(listOf("MP3", "Mono"), audioDetail("mp4a.40.34", 1))
        assertEquals(listOf("Opus", "Stereo"), audioDetail("opus", 2))
        assertEquals(listOf("FLAC", "Stereo"), audioDetail("fLaC", 2))
        // Either alone where the other is not known.
        assertEquals(listOf("Stereo"), audioDetail(null, 2))
        assertEquals(listOf("AAC"), audioDetail("mp4a.40.2", -1))
        assertEquals(emptyList<String>(), audioDetail(" ", 0))
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
