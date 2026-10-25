package cloud.nalet.chino.tv.ui.detail

import cloud.nalet.chino.tv.data.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The title page's subtitle languages: by the names it shows, in their
 *  alphabetical order, each once - never in the order of their codes. */
class SubtitleNamesTest {
    @Test
    fun `sorted by the shown name, not by the language code`() {
        val subtitles = listOf(
            Subtitle(lang = "deu"),
            Subtitle(lang = "zho"),
            Subtitle(lang = "eng"),
            Subtitle(lang = "ger"),
            Subtitle(lang = "spa"),
            Subtitle(lang = "fre"),
            Subtitle(lang = "und", label = "Signs"),
            Subtitle(lang = "zxx"),
            Subtitle(lang = "pt-BR"),
            Subtitle(lang = "por"),
        )
        // By their codes German would come before English, Chinese last.
        assertEquals(
            "Chinese, English, French, German, No dialogue, Portuguese, Portuguese (Brazil), Signs, Spanish",
            subtitleNames(subtitles),
        )
        assertNull(subtitleNames(emptyList()))
    }

    @Test
    fun `the names that fit are listed and the rest counted, none cut`() {
        val names = listOf("Arabic", "Chinese", "English", "French", "German")
        // Room for all of them.
        assertEquals("Arabic, Chinese, English, French, German", namesThatFit(names) { true })
        // Room for 30 letters: as many as fit beside the count of the rest.
        assertEquals("Arabic, Chinese, +3 more", namesThatFit(names) { it.length <= 30 })
        assertEquals("Arabic, Chinese, English, +2 more", namesThatFit(names) { it.length <= 33 })
        // Room for none with the count: the count alone.
        assertEquals("+5 more", namesThatFit(names) { it.length <= 10 })
        // One name is never counted: it shows, cut by the line if it must.
        assertEquals("Arabic", namesThatFit(listOf("Arabic")) { false })
    }
}
