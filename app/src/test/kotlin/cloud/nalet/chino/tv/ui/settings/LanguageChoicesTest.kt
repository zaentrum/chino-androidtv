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
    fun `the subtitle setting - off as the player says it, then the server's languages in its order, by the player's names`() {
        val choices = subtitleLanguageChoices(listOf("en", "de", "zh-Hans"), "off", device = "fr")
        assertEquals(
            listOf("off" to "off", "en" to "English", "de" to "German", "zh-Hans" to "Chinese (Simplified)"),
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
    fun `a server that names none offers every language by name, the viewer's own first, then the others in alphabetical order`() {
        // Nothing set and a TV in German: German first, never Abkhazian.
        val choices = subtitleLanguageChoices(emptyList(), "off", device = "de")
        assertEquals("off", choices.first().code)
        assertEquals("de" to "German", choices[1].let { it.code to it.name })
        val rest = choices.drop(2)
        assertEquals(EVERY_LANGUAGE.filter { it.code != "de" }, rest)
        assertTrue(rest.size > 100)
        val names = rest.map { it.name }
        assertEquals(names.sortedWith(Collator.getInstance(Locale.ENGLISH)), names)
        assertEquals(choices.map { it.name }.distinct(), choices.map { it.name })
        for (name in listOf("Chinese", "Dutch", "English", "French", "Japanese", "Korean")) {
            assertTrue(name, name in names)
        }
        // Names, never codes.
        for (c in rest) assertFalse(c.code, c.name.equals(c.code, ignoreCase = true))
        // The one set, then the TV's; each once.
        assertEquals(listOf("off", "fr", "de"), subtitleLanguageChoices(emptyList(), "fr", device = "de").take(3).map { it.code })
        assertEquals(
            listOf("off", "de", EVERY_LANGUAGE.first().code),
            subtitleLanguageChoices(emptyList(), "ger", device = "de").take(3).map { it.code },
        )
        // A TV in Chinese of China: its script.
        assertEquals("Chinese (Simplified)", subtitleLanguageChoices(emptyList(), "off", device = "zh-Hans")[1].name)
        // No TV language known: every language in alphabetical order.
        assertEquals(EVERY_LANGUAGE, subtitleLanguageChoices(emptyList(), "off").drop(1))
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
    fun `the audio setting - Original, then every language, the one set and the TV's first`() {
        val choices = audioLanguageChoices("orig")
        assertEquals("orig" to "Original", choices.first().let { it.code to it.name })
        assertEquals(EVERY_LANGUAGE, choices.drop(1))
        assertEquals("German", chosenLanguage(audioLanguageChoices("de"), "de")?.name)
        val own = audioLanguageChoices("ja", device = "en")
        assertEquals(listOf("orig", "ja", "en"), own.take(3).map { it.code })
        assertEquals(EVERY_LANGUAGE.filter { it.code !in setOf("ja", "en") }, own.drop(3))
        assertEquals(listOf("orig", "en"), audioLanguageChoices("orig", device = "en").take(2).map { it.code })
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
