package cloud.nalet.chino.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audio and subtitle panels' labels: the language first, "No dialogue"
 *  for zxx, "Unknown" for none; chino-web's languages.test.ts cases. */
class TrackLabelsTest {
    @Test
    fun languagesByTheirEnglishNameInEverySpelling() {
        for (tag in listOf("ger", "deu", "de", "DE", "de-CH")) assertEquals(tag, "German", languageName(tag))
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
    fun subtitlesByLanguageWithWhatTheirLabelSaysBeyondIt() {
        assertEquals(
            listOf(
                "German", "Dutch", "English · SDH", "English [CC]", "English", "German (forced)",
                "No dialogue", "No dialogue · Signs", "Signs & Songs", "Unknown", "Unknown (2)",
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
}
