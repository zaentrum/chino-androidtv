package cloud.nalet.chino.tv.ui.settings

import cloud.nalet.chino.tv.ui.player.languageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

/** Settings' languages (docs/chino, subtitles: the setting; playback: the
 *  audio setting): the server's, by the player's names, or every language. */
class LanguageChoicesTest {
    @Test
    fun `the subtitle setting - Off, then the server's languages in its order, by the player's names`() {
        val choices = subtitleLanguageChoices(listOf("en", "de", "zh-Hans"), "off")
        assertEquals(
            listOf("off" to "Off", "en" to "English", "de" to "German", "zh-Hans" to "Chinese (Simplified)"),
            choices.map { it.code to it.name },
        )
        // One language, one name: the menu's.
        for (c in choices.drop(1)) assertEquals(languageName(c.code), c.name)
    }

    @Test
    fun `the server's tags as it writes them, each once`() {
        val choices = subtitleLanguageChoices(listOf("ger", "de", "zh-hans", " ", "pt_br"), null)
        assertEquals(listOf("off", "de", "zh-Hans", "pt-BR"), choices.map { it.code })
        assertEquals("Portuguese (Brazil)", choices.last().name)
    }

    @Test
    fun `a server that names none offers every language by name, in alphabetical order`() {
        val choices = subtitleLanguageChoices(emptyList(), "off")
        assertEquals("off", choices.first().code)
        val languages = choices.drop(1)
        assertEquals(EVERY_LANGUAGE, languages)
        assertTrue(languages.size > 100)
        val names = languages.map { it.name }
        assertEquals(names.sortedWith(Collator.getInstance(Locale.ENGLISH)), names)
        assertEquals(names.distinct(), names)
        for (name in listOf("Chinese", "Dutch", "English", "French", "German", "Japanese", "Korean")) {
            assertTrue(name, name in names)
        }
        // Names, never codes.
        for (c in languages) assertFalse(c.code, c.name.equals(c.code, ignoreCase = true))
    }

    @Test
    fun `a language set before that the server no longer names stays, so the setting says what is set`() {
        val choices = subtitleLanguageChoices(listOf("en", "de"), "nl")
        assertEquals(listOf("off", "en", "de", "nl"), choices.map { it.code })
        assertEquals("Dutch", chosenLanguage(choices, "nl")?.name)
        // Off is no language to keep.
        assertEquals(listOf("off", "en", "de"), subtitleLanguageChoices(listOf("en", "de"), "off").map { it.code })
    }

    @Test
    fun `the audio setting - Original, then every language`() {
        val choices = audioLanguageChoices("orig")
        assertEquals("orig" to "Original", choices.first().let { it.code to it.name })
        assertEquals(EVERY_LANGUAGE, choices.drop(1))
        assertEquals("German", chosenLanguage(audioLanguageChoices("de"), "de")?.name)
    }

    @Test
    fun `a stored setting finds its entry by its language`() {
        val choices = subtitleLanguageChoices(listOf("en", "de", "zh-Hans"), null)
        assertEquals("de", chosenLanguage(choices, "ger")?.code)
        assertEquals("zh-Hans", chosenLanguage(choices, "zh-hans")?.code)
        assertEquals("off", chosenLanguage(choices, "off")?.code)
        assertNull(chosenLanguage(choices, "fr"))
        assertNull(chosenLanguage(choices, null))
    }
}
